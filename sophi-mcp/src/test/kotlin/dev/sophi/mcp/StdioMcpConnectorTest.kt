package dev.sophi.mcp

import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class StdioMcpConnectorTest : FunSpec({

    test("connect spawns the server, discovers ping, and calls it") {
        val classpath = System.getProperty("java.class.path")
        val config = McpServerConfig(
            name = "test",
            transport = McpTransport.STDIO,
            command = listOf(
                "java",
                // kotlin-logging prints a one-line "active logger factory" diagnostic via println()
                // (bypassing slf4j/logback entirely) unless this is set. Since stdout is the MCP
                // stdio transport's JSON-RPC channel for this subprocess, that stray line would
                // otherwise corrupt the framing the SDK's ReadBuffer expects on the client side.
                "-Dkotlin-logging.logStartupMessage=false",
                "-cp",
                classpath,
                "dev.sophi.mcp.TestMcpServerMainKt"
            )
        )
        val connector = StdioMcpConnector()

        val (toolNames, reply) = runBlocking {
            val session = connector.connect(config)
            val tools = session.listTools()
            val reply = session.callTool("ping", "{}")
            session.close()
            tools.map { it.name } to reply
        }

        toolNames shouldBe listOf("ping")
        reply shouldBe "pong"
    }
})
