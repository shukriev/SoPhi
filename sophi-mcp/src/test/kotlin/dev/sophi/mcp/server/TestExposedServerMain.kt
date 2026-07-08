package dev.sophi.mcp.server

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File

private class EchoTool : Tool {
    override val name = "echo"
    override val description = "Echoes its input back"
    override val parametersJson = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""
    override suspend fun execute(argumentsJson: String): String = argumentsJson
}

/**
 * A destructive tool whose [execute] has a real, checkable side effect (writing a marker file
 * whose path is passed via the `DANGER_TOOL_MARKER_FILE` env var) rather than just returning a
 * distinctive string. This lets [McpServerBuilderTest] prove [execute] genuinely never ran when
 * denied by [buildMcpServer] — asserting the marker file's absence — instead of relying on a
 * denial-message string match that could coincidentally look right even if [execute] had run.
 */
private class DangerTool : Tool {
    override val name = "danger"
    override val description = "A destructive tool that should never actually run when exposed"
    override val riskLevel = RiskLevel.DESTRUCTIVE
    override val parametersJson = """{"type":"object","properties":{}}"""
    override suspend fun execute(argumentsJson: String): String {
        System.getenv("DANGER_TOOL_MARKER_FILE")?.let { File(it).writeText("EXECUTED") }
        return "SHOULD NOT RUN"
    }
}

/**
 * Test-only fixture: launched by [McpServerBuilderTest] as a real subprocess via
 * `java -cp <classpath> dev.sophi.mcp.server.TestExposedServerMainKt`, exposing one SAFE
 * tool ("echo") and one DESTRUCTIVE tool ("danger") through [buildMcpServer].
 */
fun main() = runBlocking {
    val server = buildMcpServer(listOf(EchoTool(), DangerTool()), setOf("echo", "danger"))
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered()
    )
    val closed = CompletableDeferred<Unit>()
    transport.onClose { closed.complete(Unit) }
    server.createSession(transport)
    closed.await()
}
