package dev.sophi.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The provider types [CompanionSettings.providerType] accepts. */
object ProviderTypes {
    const val CLAUDE = "claude"
    const val OPENAI_COMPAT = "openai-compat"
    val ALL = setOf(CLAUDE, OPENAI_COMPAT)
}

/** Human-readable label for a [ProviderTypes] value, for display in the profile list. */
fun providerDisplayName(providerType: String): String = when (providerType) {
    ProviderTypes.CLAUDE -> "Claude"
    ProviderTypes.OPENAI_COMPAT -> "OpenAI-compatible"
    else -> providerType
}

@Serializable
data class CompanionSettings(
    val providerType: String = ProviderTypes.CLAUDE,
    val model: String = "claude-sonnet-4-5",
    /** Required for [ProviderTypes.OPENAI_COMPAT]. Ollama: `http://localhost:11434/v1`, vLLM: `http://localhost:8000/v1`. */
    val baseUrl: String? = null,
    val apiKey: String? = null,
    /**
     * Total context window of [model], in tokens. Sophi compacts a turn's earlier tool rounds
     * once 80% of this is used, so a value larger than the model's real window means compaction
     * never fires and the model overflows instead. The default is a Claude-sized number — local
     * models are usually far smaller, so set this explicitly when using one.
     */
    val contextWindowTokens: Int = 200_000,
    /** Max tokens the model may generate per response — the equivalent of `sophi --max-tokens`. */
    val maxTokens: Int = 4096,
    val sessionsDir: String = System.getProperty("user.home") + "/.sophi/sessions",
    val mcpConfigPath: String = System.getProperty("user.home") + "/.sophi/mcp.json",
    /** Directory of `*.md` AgentDefinition files this companion's scheduled tasks may delegate
     *  to via subagentType. Matches the CLI's `--agents-dir` default. */
    val agentsDir: String = System.getProperty("user.home") + "/.sophi/agents",
    /** Port the embedded hub (ADR-023) listens on for CLI sessions to register with. */
    val hubPort: Int = 8765,
    /** Enables Jane's Theory long-term memory (experimental). Requires [embeddingModel] and
     *  [embeddingBaseUrl]. Hand-edit this file to turn it on — no setup-wizard field yet. */
    val memoryEnabled: Boolean = false,
    /** Required when [memoryEnabled]. e.g. nomic-embed-text (Ollama) or text-embedding-3-small. */
    val embeddingModel: String? = null,
    /** Required when [memoryEnabled]. Ollama: http://localhost:11434/v1, vLLM: http://localhost:8000/v1. */
    val embeddingBaseUrl: String? = null,
    val embeddingApiKey: String? = null,
    val embeddingDimensions: Int = 1536,
    /** Enables push-to-talk speech-to-text (experimental) — hold [pttHotkey] or the mic button to
     *  transcribe what you say into a normal chat turn. Independent of [ttsEnabled]; both share
     *  the four path fields below. */
    val sttEnabled: Boolean = false,
    /** Enables spoken replies via local piper (experimental) — every reply is read aloud,
     *  whether the turn was typed or sent via [sttEnabled]. Independent of [sttEnabled]. */
    val ttsEnabled: Boolean = false,
    /** Path to a local whisper.cpp executable. Used when [sttEnabled]. */
    val whisperBinaryPath: String? = null,
    /** Path to a whisper.cpp ggml model file. Used when [sttEnabled]. */
    val whisperModelPath: String? = null,
    /** Python interpreter inside the installed piper runtime; JSON key kept as piperBinaryPath for back-compat. */
    @SerialName("piperBinaryPath")
    val piperPythonPath: String? = null,
    /** Path to a piper voice model (.onnx). Used when [ttsEnabled]. */
    val piperVoicePath: String? = null,
    /** Held to record while the Chat tab's message field does not have focus. */
    val pttHotkey: String = "Right Option",
    /** Root directory sophi-companion's file/bash tools are confined to. Sandboxed by default —
     *  companion is an always-running background app, and some tool calls can fire from
     *  unattended scheduled/goal-mode runs with nobody watching; point this at a real projects
     *  folder for CLI-equivalent reach, opted into explicitly rather than granted by accident. */
    val workspaceDir: String = System.getProperty("user.home") + "/.sophi/workspace",
    /** Named provider configs saved from the Settings tab so you can flip between e.g. a remote
     *  Claude setup and a local Ollama one without re-typing model/baseUrl/apiKey each time. */
    val profiles: List<LlmProfile> = emptyList()
)

/** A saved snapshot of the provider fields, switchable via [CompanionSettings.applyProfile]. */
@Serializable
data class LlmProfile(
    val name: String,
    val providerType: String,
    val model: String,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val contextWindowTokens: Int = 200_000,
    val maxTokens: Int = 4096
)

/** Copies [profile]'s provider fields onto these settings, leaving everything else (voice, memory,
 *  workspace, other saved profiles) untouched. */
fun CompanionSettings.applyProfile(profile: LlmProfile): CompanionSettings = copy(
    providerType = profile.providerType,
    model = profile.model,
    baseUrl = profile.baseUrl,
    apiKey = profile.apiKey,
    contextWindowTokens = profile.contextWindowTokens,
    maxTokens = profile.maxTokens
)

/**
 * Returns a human-readable reason this config can't be used, or `null` if it's usable.
 * Checked by the setup screen before saving and by [SettingsStore.load] on read, so a
 * hand-edited file fails with an explanation rather than a stack trace deep in provider setup.
 */
fun CompanionSettings.validationError(): String? = when {
    providerType !in ProviderTypes.ALL ->
        "Unknown providerType '$providerType' — expected one of ${ProviderTypes.ALL.joinToString(", ")}"
    model.isBlank() -> "model must not be blank"
    providerType == ProviderTypes.OPENAI_COMPAT && baseUrl.isNullOrBlank() ->
        "baseUrl is required for providerType '${ProviderTypes.OPENAI_COMPAT}' " +
            "(Ollama: http://localhost:11434/v1, vLLM: http://localhost:8000/v1)"
    contextWindowTokens <= 0 -> "contextWindowTokens must be greater than 0"
    maxTokens <= 0 -> "maxTokens must be greater than 0"
    maxTokens > contextWindowTokens ->
        "maxTokens ($maxTokens) must not exceed contextWindowTokens ($contextWindowTokens)"
    memoryEnabled && embeddingModel.isNullOrBlank() -> "embeddingModel is required when memoryEnabled is true"
    memoryEnabled && embeddingBaseUrl.isNullOrBlank() -> "embeddingBaseUrl is required when memoryEnabled is true"
    else -> null
}

class SettingsStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): CompanionSettings? =
        if (!path.exists()) null
        else json.decodeFromString<CompanionSettings>(path.readText())

    fun save(settings: CompanionSettings) {
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(json.encodeToString(settings))
    }

    /**
     * The API key to use, or `null` for none. `ANTHROPIC_API_KEY` is only consulted for the
     * Claude provider — a local Ollama/vLLM server should not receive an Anthropic key as its
     * bearer token just because the variable happens to be exported.
     */
    fun resolveApiKey(settings: CompanionSettings): String? =
        settings.apiKey
            ?: if (settings.providerType == ProviderTypes.CLAUDE) System.getenv("ANTHROPIC_API_KEY") else null
}
