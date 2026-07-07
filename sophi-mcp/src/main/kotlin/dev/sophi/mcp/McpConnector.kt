package dev.sophi.mcp

import dev.sophi.mcp.config.McpServerConfig

interface McpConnector {
    suspend fun connect(config: McpServerConfig): McpSession
}
