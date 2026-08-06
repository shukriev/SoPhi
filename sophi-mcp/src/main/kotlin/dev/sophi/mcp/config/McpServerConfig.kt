package dev.sophi.mcp.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class McpTransport {
    @SerialName("stdio") STDIO,
    @SerialName("http") HTTP
}

@Serializable
data class McpServerConfig(
    val name: String,
    val transport: McpTransport,
    val command: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,
    val safeTools: List<String> = emptyList(),
    val enabled: Boolean = true
)

@Serializable
data class McpConfig(val servers: List<McpServerConfig> = emptyList())
