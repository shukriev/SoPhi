package dev.sophi.mcp.server

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.mcp.StdioMcpConnector
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

private fun fakeTool(toolName: String, risk: RiskLevel = RiskLevel.SAFE, parametersJson: String = "{}") =
    object : Tool {
        override val name = toolName
        override val description = "fake tool for testing"
        override val parametersJson = parametersJson
        override fun riskLevel(argumentsJson: String) = risk
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

    test("buildMcpServer parses parametersJson into the SDK's inputSchema, not an empty default") {
        val grep = fakeTool(
            "grep",
            parametersJson = """{"type":"object","properties":{"pattern":{"type":"string"}},"required":["pattern"]}"""
        )

        val server = buildMcpServer(listOf(grep), setOf("grep"))

        val inputSchema = server.tools.getValue("grep").tool.inputSchema
        inputSchema.properties?.keys shouldBe setOf("pattern")
        inputSchema.required shouldBe listOf("pattern")
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
            env = mapOf(
                "DANGER_TOOL_MARKER_FILE" to markerFile.toString(),
                "CAREFUL_TOOL_MARKER_FILE" to Files.createTempFile("careful-tool-marker", ".txt").toString()
            )
        )
        val connector = StdioMcpConnector()

        val (remoteTools, echoReply, dangerReply) = runBlocking {
            val session = connector.connect(config)
            val tools = session.listTools()
            val echo = session.callTool("echo", """{"text":"hi"}""")
            val danger = session.callTool("danger", "{}")
            session.close()
            Triple(tools, echo, danger)
        }

        remoteTools.map { it.name } shouldBe listOf("echo", "danger", "careful")
        echoReply shouldBe """{"text":"hi"}"""
        dangerReply shouldBe "Denied: 'danger' is not a SAFE tool and cannot be called via mcp-serve"
        // The genuine proof: DangerTool.execute() was never invoked, so it never wrote the marker.
        Files.exists(markerFile) shouldBe false
    }

    test("exposed CAUTION tool is denied over a real stdio round-trip, same as DESTRUCTIVE") {
        val classpath = System.getProperty("java.class.path")
        val carefulMarkerFile = Files.createTempFile("careful-tool-marker", ".txt")
        Files.delete(carefulMarkerFile)

        val config = McpServerConfig(
            name = "test",
            transport = McpTransport.STDIO,
            command = listOf("java", "-cp", classpath, "dev.sophi.mcp.server.TestExposedServerMainKt"),
            env = mapOf(
                "DANGER_TOOL_MARKER_FILE" to Files.createTempFile("danger-tool-marker", ".txt").toString(),
                "CAREFUL_TOOL_MARKER_FILE" to carefulMarkerFile.toString()
            )
        )
        val connector = StdioMcpConnector()

        val carefulReply = runBlocking {
            val session = connector.connect(config)
            val reply = session.callTool("careful", "{}")
            session.close()
            reply
        }

        carefulReply shouldBe "Denied: 'careful' is not a SAFE tool and cannot be called via mcp-serve"
        Files.exists(carefulMarkerFile) shouldBe false
    }

    test("exposed tool's inputSchema round-trips over stdio with real properties/required, not an empty default") {
        val classpath = System.getProperty("java.class.path")
        val config = McpServerConfig(
            name = "test",
            transport = McpTransport.STDIO,
            command = listOf("java", "-cp", classpath, "dev.sophi.mcp.server.TestExposedServerMainKt"),
            env = mapOf("DANGER_TOOL_MARKER_FILE" to Files.createTempFile("danger-tool-marker", ".txt").toString())
        )
        val connector = StdioMcpConnector()

        val remoteTools = runBlocking {
            val session = connector.connect(config)
            val tools = session.listTools()
            session.close()
            tools
        }

        val echo = remoteTools.first { it.name == "echo" }
        echo.inputSchemaJson shouldContain "\"properties\""
        echo.inputSchemaJson shouldContain "\"text\""
        echo.inputSchemaJson shouldContain "\"required\""
    }
})
