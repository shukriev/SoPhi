package dev.sophi.mcp.config

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class McpConfigWriter {
    private val json = Json { prettyPrint = true }

    fun write(path: Path, config: McpConfig) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, json.encodeToString(McpConfig.serializer(), config))
    }
}
