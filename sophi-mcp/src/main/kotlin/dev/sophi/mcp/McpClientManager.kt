package dev.sophi.mcp

import dev.sophi.core.tools.Tool
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import kotlinx.coroutines.runBlocking

class McpClientManager(
    private val stdioConnector: McpConnector = StdioMcpConnector(),
    private val httpConnector: McpConnector = StreamableHttpMcpConnector()
) : AutoCloseable {

    private val sessions = mutableMapOf<String, McpSession>()

    suspend fun connect(configs: List<McpServerConfig>): List<Tool> =
        configs.flatMap { connectOne(it) }

    suspend fun connectOne(config: McpServerConfig): List<Tool> {
        val connector = when (config.transport) {
            McpTransport.STDIO -> stdioConnector
            McpTransport.HTTP -> httpConnector
        }
        return try {
            val session = connector.connect(config)
            sessions[config.name] = session
            val safeTools = config.safeTools.toSet()
            session.listTools().map { remoteTool -> McpTool(session, config.name, remoteTool, safeTools) }
        } catch (e: Exception) {
            System.err.println("Warning: MCP server '${config.name}' failed to connect or list tools: ${e.message}")
            emptyList()
        }
    }

    suspend fun disconnect(serverName: String) {
        sessions.remove(serverName)?.let { session ->
            try {
                session.close()
            } catch (e: Exception) {
                System.err.println("Warning: failed to close MCP session '$serverName': ${e.message}")
            }
        }
    }

    override fun close() {
        runBlocking {
            sessions.values.forEach { session ->
                try {
                    session.close()
                } catch (e: Exception) {
                    System.err.println("Warning: failed to close an MCP session: ${e.message}")
                }
            }
            sessions.clear()
        }
    }
}
