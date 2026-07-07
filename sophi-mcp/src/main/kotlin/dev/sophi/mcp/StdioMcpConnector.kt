package dev.sophi.mcp

import dev.sophi.mcp.config.McpServerConfig
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Spawns the configured command as a subprocess and connects to it over the MCP SDK's stdio
 * transport.
 *
 * Note: the real SDK's [StdioClientTransport] constructor takes `input`/`output` (not
 * `inputStream`/`outputStream`) as its parameter names.
 */
class StdioMcpConnector : McpConnector {
    override suspend fun connect(config: McpServerConfig): McpSession {
        require(config.command.isNotEmpty()) { "stdio MCP server '${config.name}' requires a non-empty command" }
        val processBuilder = ProcessBuilder(config.command).redirectErrorStream(false)
        processBuilder.environment().putAll(config.env)
        val process = processBuilder.start()

        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered()
        )
        val client = Client(clientInfo = Implementation(name = "sophi", version = "1.0.0"))
        client.connect(transport)
        return SdkMcpSession(client)
    }
}
