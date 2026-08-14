package dev.sophi.companion

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
    val embeddingDimensions: Int = 1536
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
