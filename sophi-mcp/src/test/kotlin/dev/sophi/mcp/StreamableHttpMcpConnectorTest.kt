package dev.sophi.mcp

import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class StreamableHttpMcpConnectorTest : FunSpec({

    test("connect discovers and calls a tool over Streamable HTTP") {
        val server = startTestMcpHttpServer(port = 18181)
        try {
            val connector = StreamableHttpMcpConnector()
            val config = McpServerConfig(name = "test", transport = McpTransport.HTTP, url = "http://localhost:18181/mcp")

            val (toolNames, reply) = runBlocking {
                val session = connector.connect(config)
                val tools = session.listTools()
                val reply = session.callTool("ping", "{}")
                session.close()
                tools.map { it.name } to reply
            }

            toolNames shouldBe listOf("ping")
            reply shouldBe "pong"
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        }
    }
})
