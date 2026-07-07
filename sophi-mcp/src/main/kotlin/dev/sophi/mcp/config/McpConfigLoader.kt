package dev.sophi.mcp.config

import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class McpConfigLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(path: Path): McpConfig {
        if (!path.exists()) return McpConfig()
        return json.decodeFromString(McpConfig.serializer(), path.readText())
    }
}
