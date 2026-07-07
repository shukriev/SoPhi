package dev.sophi.mcp

import dev.sophi.core.tools.Tool
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import kotlinx.coroutines.runBlocking

class McpClientManager(
    private val stdioConnector: McpConnector = StdioMcpConnector(),
    private val httpConnector: McpConnector = StreamableHttpMcpConnector()
) : AutoCloseable {

    private val openSessions = mutableListOf<McpSession>()

    suspend fun connect(configs: List<McpServerConfig>): List<Tool> {
        val tools = mutableListOf<Tool>()
        for (config in configs) {
            val connector = when (config.transport) {
                McpTransport.STDIO -> stdioConnector
                McpTransport.HTTP -> httpConnector
            }
            try {
                val session = connector.connect(config)
                openSessions.add(session)
                val safeTools = config.safeTools.toSet()
                session.listTools().forEach { remoteTool ->
                    tools.add(McpTool(session, config.name, remoteTool, safeTools))
                }
            } catch (e: Exception) {
                System.err.println("Warning: MCP server '${config.name}' failed to connect or list tools: ${e.message}")
            }
        }
        return tools
    }

    override fun close() {
        runBlocking {
            openSessions.forEach { session ->
                try {
                    session.close()
                } catch (e: Exception) {
                    System.err.println("Warning: failed to close an MCP session: ${e.message}")
                }
            }
        }
    }
}
