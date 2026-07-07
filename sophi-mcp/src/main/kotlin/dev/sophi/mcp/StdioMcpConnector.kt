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

        val client = connectOrDestroy(process) {
            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered()
            )
            val client = Client(clientInfo = Implementation(name = "sophi", version = "1.0.0"))
            client.connect(transport)
            client
        }
        return SdkMcpSession(client, process)
    }

    /**
     * Runs [connectBlock] (spawns the transport and performs the MCP handshake) against an
     * already-started [process]. If [connectBlock] throws for any reason (server crashes, hangs
     * mid-handshake, or never speaks the protocol), the already-started subprocess must not be
     * leaked: it is force-killed before the failure is propagated.
     *
     * Pulled out as its own function (rather than inlined into [connect]) so the "destroy the
     * process on failure" behavior can be exercised directly in tests with a synthetic failure,
     * independent of the real SDK's handshake timing.
     */
    internal suspend fun <T> connectOrDestroy(process: Process, connectBlock: suspend () -> T): T =
        try {
            connectBlock()
        } catch (error: Throwable) {
            process.destroyForcibly()
            throw error
        }
}
