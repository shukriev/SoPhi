package dev.sophi.mcp.server

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.mcp.StdioMcpConnector
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

private fun fakeTool(toolName: String, risk: RiskLevel = RiskLevel.SAFE) = object : Tool {
    override val name = toolName
    override val description = "fake tool for testing"
    override val parametersJson = "{}"
    override val riskLevel = risk
    override suspend fun execute(argumentsJson: String) = "ok"
}

class McpServerBuilderTest : FunSpec({

    test("buildMcpServer throws for an unknown tool name") {
        val error = shouldThrow<IllegalArgumentException> {
            buildMcpServer(listOf(fakeTool("grep")), setOf("bogus"))
        }
        error.message shouldBe "Unknown tool(s) in --expose-tools: bogus"
    }

    test("buildMcpServer throws for an empty allowlist") {
        shouldThrow<IllegalArgumentException> {
            buildMcpServer(listOf(fakeTool("grep")), emptySet())
        }
    }

    test("exposed SAFE tool executes and DESTRUCTIVE tool is denied over a real stdio round-trip") {
        val classpath = System.getProperty("java.class.path")
        // Marker file that TestExposedServerMain's DangerTool.execute() writes to if it is ever
        // actually invoked. The subprocess starts with this path pointing at a file that does not
        // exist yet, so its continued absence after calling "danger" is a genuine, state-checkable
        // proof that execute() never ran -- not just a denial-message string that happens to match.
        val markerFile = Files.createTempFile("danger-tool-marker", ".txt")
        Files.delete(markerFile)

        val config = McpServerConfig(
            name = "test",
            transport = McpTransport.STDIO,
            command = listOf("java", "-cp", classpath, "dev.sophi.mcp.server.TestExposedServerMainKt"),
            env = mapOf("DANGER_TOOL_MARKER_FILE" to markerFile.toString())
        )
        val connector = StdioMcpConnector()

        val (toolNames, echoReply, dangerReply) = runBlocking {
            val session = connector.connect(config)
            val tools = session.listTools().map { it.name }
            val echo = session.callTool("echo", """{"text":"hi"}""")
            val danger = session.callTool("danger", "{}")
            session.close()
            Triple(tools, echo, danger)
        }

        toolNames shouldBe listOf("echo", "danger")
        echoReply shouldBe """{"text":"hi"}"""
        dangerReply shouldBe "Denied: 'danger' is a destructive tool and cannot be called via mcp-serve"
        // The genuine proof: DangerTool.execute() was never invoked, so it never wrote the marker.
        Files.exists(markerFile) shouldBe false
    }
})
