package dev.sophi.companion

import dev.sophi.schedule.model.CronSchedules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The provider types [LlmProfile.providerType] accepts. */
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

/**
 * A full connection: one LLM + its memory config, switchable as a unit via [CompanionSettings.
 * activeProfileName]. Identified by [name] (fixed at creation — no rename in this pass).
 */
@Serializable
data class LlmProfile(
    val name: String,
    val providerType: String,
    val model: String,
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
    /**
     * How long to wait for the next chunk of a streaming response before giving up, for
     * [ProviderTypes.OPENAI_COMPAT]. Local reasoning models can spend well over a minute on hidden
     * chain-of-thought before emitting any content — too short a value aborts the request out from
     * under a model that's still generating rather than one that's actually stuck. Not used for
     * Claude (Anthropic's client manages its own timeout).
     */
    val requestTimeoutSeconds: Int = 300,
    /** Enables Jane's Theory long-term memory (experimental) for this profile. Requires
     *  [embeddingModel] and [embeddingBaseUrl]. */
    val memoryEnabled: Boolean = false,
    /** Required when [memoryEnabled]. e.g. nomic-embed-text (Ollama) or text-embedding-3-small. */
    val embeddingModel: String? = null,
    /** Required when [memoryEnabled]. Ollama: http://localhost:11434/v1, vLLM: http://localhost:8000/v1. */
    val embeddingBaseUrl: String? = null,
    val embeddingApiKey: String? = null,
    val embeddingDimensions: Int = 1536
)

@Serializable
data class CompanionSettings(
    val sessionsDir: String = System.getProperty("user.home") + "/.sophi/sessions",
    val mcpConfigPath: String = System.getProperty("user.home") + "/.sophi/mcp.json",
    /** Directory of `*.md` AgentDefinition files this companion's scheduled tasks may delegate
     *  to via subagentType. Matches the CLI's `--agents-dir` default. */
    val agentsDir: String = System.getProperty("user.home") + "/.sophi/agents",
    /** Port the embedded hub (ADR-023) listens on for CLI sessions to register with. */
    val hubPort: Int = 8765,
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
    /** Root of an Obsidian vault (or any plain-markdown notes folder) that scheduled check-ins
     *  write into — a second, separate tool root from [workspaceDir]. Unset (the default, or
     *  blank) disables check-in scheduling entirely. */
    val obsidianVaultPath: String? = null,
    /** Used by the obsidian-worklog skill to turn a bare Jira ticket ID (e.g. PROJ-123) into a
     *  markdown link. Unset leaves ticket IDs as plain text. */
    val jiraBaseUrl: String? = null,
    /** Periodic check-in prompts. Inert unless [obsidianVaultPath] is also set and non-blank. */
    val checkIns: List<CheckIn> = listOf(
        CheckIn("work-log", "What have you worked on since last check-in?", "0 11,14,17 * * *")
    ),
    /** Saved connections — each a full LLM + memory config. Never empty: [validationError] rejects
     *  an empty list, and [SettingsStore.load] migrates any pre-profiles file into a single
     *  "Default" entry. Switch which one is running via [activeProfileName]. */
    val profiles: List<LlmProfile> = listOf(
        LlmProfile(name = "Default", providerType = ProviderTypes.CLAUDE, model = "claude-sonnet-4-5")
    ),
    /** Which of [profiles] is currently running. Must match one of their [LlmProfile.name]s —
     *  [validationError] rejects a mismatch. */
    val activeProfileName: String = "Default",
    /** Passively listens while the app window is open, storing notable ambient speech as
     *  memory (see docs/superpowers/specs/2026-09-10-ambient-listening-design.md) and creating
     *  reminders/tasks it hears. Requires the same voice-tools bundle as [sttEnabled]/[ttsEnabled]
     *  (whisper only is actually used). Off by default. */
    val ambientListeningEnabled: Boolean = false
)

/** One periodic "what have you worked on" prompt. [cronExpression] is validated the same way
 *  `dev.sophi.schedule.model.Trigger.Cron` expressions are (`CronSchedules.validate`) — only cron
 *  scheduling is supported here, not the full Trigger sealed class. */
@Serializable
data class CheckIn(
    val name: String,
    val question: String,
    val cronExpression: String
)

/** The currently-running profile — [CompanionSettings.activeProfileName] resolved against
 *  [CompanionSettings.profiles]. Falls back to the first profile if the pointer doesn't match
 *  anything (a stale/hand-edited [CompanionSettings.activeProfileName]), rather than throwing.
 *  Callers must ensure [CompanionSettings.profiles] is non-empty first — already guaranteed once
 *  [validationError] has passed. Throws [NoSuchElementException] on an empty list; a call site
 *  that can't guarantee validation already ran (e.g. repairing a broken saved file) should look
 *  profiles up directly instead of calling this. */
fun CompanionSettings.activeProfile(): LlmProfile =
    profiles.find { it.name == activeProfileName } ?: profiles.first()

/**
 * Returns a human-readable reason this config can't be used, or `null` if it's usable.
 * Checked by the setup screen before saving and by [SettingsStore.load] on read, so a
 * hand-edited file fails with an explanation rather than a stack trace deep in provider setup.
 */
fun CompanionSettings.validationError(): String? {
    if (profiles.isEmpty()) return "profiles must not be empty"
    val active = profiles.find { it.name == activeProfileName }
        ?: return "activeProfileName '$activeProfileName' does not match any profile name"
    return when {
        active.providerType !in ProviderTypes.ALL ->
            "Unknown providerType '${active.providerType}' — expected one of ${ProviderTypes.ALL.joinToString(", ")}"
        active.model.isBlank() -> "model must not be blank"
        active.providerType == ProviderTypes.OPENAI_COMPAT && active.baseUrl.isNullOrBlank() ->
            "baseUrl is required for providerType '${ProviderTypes.OPENAI_COMPAT}' " +
                "(Ollama: http://localhost:11434/v1, vLLM: http://localhost:8000/v1)"
        active.contextWindowTokens <= 0 -> "contextWindowTokens must be greater than 0"
        active.maxTokens <= 0 -> "maxTokens must be greater than 0"
        active.maxTokens > active.contextWindowTokens ->
            "maxTokens (${active.maxTokens}) must not exceed contextWindowTokens (${active.contextWindowTokens})"
        active.requestTimeoutSeconds <= 0 -> "requestTimeoutSeconds must be greater than 0"
        active.memoryEnabled && active.embeddingModel.isNullOrBlank() -> "embeddingModel is required when memoryEnabled is true"
        active.memoryEnabled && active.embeddingBaseUrl.isNullOrBlank() -> "embeddingBaseUrl is required when memoryEnabled is true"
        checkIns.any { it.name.isBlank() } -> "each checkIns entry must have a non-blank name"
        checkIns.any { it.question.isBlank() } -> "each checkIns entry must have a non-blank question"
        checkIns.mapNotNull { ci -> CronSchedules.validate(ci.cronExpression)?.let { "${ci.name}: $it" } }.firstOrNull() != null ->
            "invalid checkIns cronExpression — " +
                checkIns.mapNotNull { ci -> CronSchedules.validate(ci.cronExpression)?.let { "${ci.name}: $it" } }.first()
        else -> null
    }
}

class SettingsStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): CompanionSettings? {
        if (!path.exists()) return null
        val raw = json.parseToJsonElement(path.readText()).jsonObject
        val existingProfiles = raw["profiles"] as? JsonArray
        val patched = if (existingProfiles == null || existingProfiles.isEmpty()) migrateToProfile(raw) else raw
        return json.decodeFromJsonElement<CompanionSettings>(patched)
    }

    /**
     * A pre-profiles (or profiles-less) file has its LLM/memory config as old top-level keys —
     * read them directly off [raw] (each optional, falling back to [LlmProfile]'s own defaults)
     * and inject a synthesized `"Default"` profile as `profiles`/`activeProfileName`. The old
     * top-level keys are left untouched in [raw]; they're simply unread by the final decode
     * (`ignoreUnknownKeys = true`). Every other field (workspaceDir, voice paths, etc.) passes
     * through [raw] unchanged.
     */
    private fun migrateToProfile(raw: JsonObject): JsonObject {
        fun str(key: String): String? = (raw[key] as? JsonPrimitive)?.contentOrNull
        fun int(key: String, default: Int): Int = (raw[key] as? JsonPrimitive)?.intOrNull ?: default
        fun bool(key: String, default: Boolean): Boolean = (raw[key] as? JsonPrimitive)?.booleanOrNull ?: default

        val defaultProfile = LlmProfile(
            name = "Default",
            providerType = str("providerType") ?: ProviderTypes.CLAUDE,
            model = str("model") ?: "claude-sonnet-4-5",
            baseUrl = str("baseUrl"),
            apiKey = str("apiKey"),
            contextWindowTokens = int("contextWindowTokens", 200_000),
            maxTokens = int("maxTokens", 4096),
            requestTimeoutSeconds = int("requestTimeoutSeconds", 300),
            memoryEnabled = bool("memoryEnabled", false),
            embeddingModel = str("embeddingModel"),
            embeddingBaseUrl = str("embeddingBaseUrl"),
            embeddingApiKey = str("embeddingApiKey"),
            embeddingDimensions = int("embeddingDimensions", 1536)
        )
        val profilesElement = JsonArray(listOf(json.encodeToJsonElement(defaultProfile)))
        return JsonObject(raw + mapOf("profiles" to profilesElement, "activeProfileName" to JsonPrimitive("Default")))
    }

    fun save(settings: CompanionSettings) {
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(json.encodeToString(settings))
    }

    /**
     * The API key to use, or `null` for none. `ANTHROPIC_API_KEY` is only consulted for the
     * Claude provider — a local Ollama/vLLM server should not receive an Anthropic key as its
     * bearer token just because the variable happens to be exported. Looks the active profile up
     * directly (rather than via [activeProfile]) so an empty [CompanionSettings.profiles] returns
     * `null` instead of throwing — [resolveApiKey] can be called before [validationError] has
     * been checked.
     */
    fun resolveApiKey(settings: CompanionSettings): String? {
        val active = settings.profiles.find { it.name == settings.activeProfileName }
            ?: settings.profiles.firstOrNull()
            ?: return null
        return active.apiKey ?: if (active.providerType == ProviderTypes.CLAUDE) System.getenv("ANTHROPIC_API_KEY") else null
    }
}
