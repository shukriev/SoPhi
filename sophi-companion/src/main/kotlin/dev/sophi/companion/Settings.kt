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

@Serializable
data class CompanionSettings(
    val providerType: String = "claude",
    val model: String = "claude-sonnet-4-5",
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val contextWindowTokens: Int = 200_000,
    val sessionsDir: String = System.getProperty("user.home") + "/.sophi/sessions",
    val mcpConfigPath: String = System.getProperty("user.home") + "/.sophi/mcp.json"
)

class SettingsStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): CompanionSettings? =
        if (!path.exists()) null
        else json.decodeFromString<CompanionSettings>(path.readText())

    fun save(settings: CompanionSettings) {
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(json.encodeToString(settings))
    }

    fun resolveApiKey(settings: CompanionSettings): String? =
        settings.apiKey ?: System.getenv("ANTHROPIC_API_KEY")
}
