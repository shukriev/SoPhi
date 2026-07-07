package dev.sophi.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * Test-only fixture: an in-process ktor server hosting a tiny MCP server ("ping" -> "pong") over
 * the Streamable HTTP transport, used by [StreamableHttpMcpConnectorTest] to prove a real HTTP
 * round-trip against [StreamableHttpMcpConnector] (an actual bound port, actual HTTP requests —
 * no mocking of the transport).
 *
 * Note: the real SDK's `Application.mcpStreamableHttp(path, ...)` extension (package
 * `io.modelcontextprotocol.kotlin.sdk.server`) takes a `RoutingContext.() -> Server` factory
 * block, matching the shape used by [TestMcpServerMain]'s stdio fixture from Task 3.
 */
fun startTestMcpHttpServer(port: Int): EmbeddedServer<*, *> =
    embeddedServer(CIO, port = port) {
        mcpStreamableHttp(path = "/mcp") {
            Server(
                serverInfo = Implementation(name = "sophi-test-server", version = "1.0.0"),
                options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()))
            ).apply {
                addTool(name = "ping", description = "Replies pong") { _ ->
                    CallToolResult(content = listOf(TextContent("pong")))
                }
            }
        }
    }.start(wait = false)
