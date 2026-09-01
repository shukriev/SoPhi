package dev.sophi.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Test-only fixture: a tiny MCP server compiled into test-classes, launched by
 * [StdioMcpConnectorTest] as a real subprocess via `java -cp <classpath> dev.sophi.mcp.TestMcpServerMainKt`.
 *
 * It registers one tool ("ping") that always replies "pong", so the integration test doesn't
 * depend on any real external MCP server or network access.
 */
fun main() = runBlocking {
    val server = Server(
        serverInfo = Implementation(name = "sophi-test-server", version = "1.0.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()))
    )

    server.addTool(name = "ping", description = "Replies pong") { _ ->
        CallToolResult(content = listOf(TextContent("pong")))
    }
    server.addTool(name = "path", description = "Replies this process's PATH env var") { _ ->
        CallToolResult(content = listOf(TextContent(System.getenv("PATH") ?: "")))
    }

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered()
    )

    // Server.connect() does not exist on the real SDK; a session is created (and connected) via
    // Server.createSession(transport). Block main() until the transport closes (e.g. the test
    // client disconnects), otherwise main() would return immediately after createSession launches
    // its background pump coroutines, killing the subprocess before it can serve any requests.
    val closed = CompletableDeferred<Unit>()
    transport.onClose { closed.complete(Unit) }
    server.createSession(transport)
    closed.await()
}
