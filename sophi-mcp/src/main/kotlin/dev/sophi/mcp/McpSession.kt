package dev.sophi.mcp

data class RemoteToolInfo(
    val name: String,
    val description: String,
    val inputSchemaJson: String
)

interface McpSession {
    suspend fun listTools(): List<RemoteToolInfo>
    suspend fun callTool(name: String, argumentsJson: String): String
    suspend fun close()
}
