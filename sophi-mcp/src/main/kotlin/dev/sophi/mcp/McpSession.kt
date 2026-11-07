package dev.sophi.mcp

data class RemoteToolInfo(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    /** The server's own claim that the tool only reads; trusted only by plan mode, never for approval. */
    val readOnlyHint: Boolean = false,
)

interface McpSession {
    suspend fun listTools(): List<RemoteToolInfo>
    suspend fun callTool(name: String, argumentsJson: String): String
    suspend fun close()
}

/** A session that can say when its server's tool list changed (`notifications/tools/list_changed`). */
interface ToolsChangeSource {
    fun onToolsChanged(handler: suspend () -> Unit)
}
