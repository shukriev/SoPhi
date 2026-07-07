package dev.sophi.mcp

import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class McpClientManagerTest : FunSpec({

    test("connect registers namespaced tools from a successfully connected server") {
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns listOf(RemoteToolInfo("read_file", "reads a file", "{}"))
        val stdioConnector = mockk<McpConnector>()
        coEvery { stdioConnector.connect(any()) } returns session
        val manager = McpClientManager(stdioConnector = stdioConnector, httpConnector = mockk())

        val tools = runBlocking {
            manager.connect(listOf(McpServerConfig(name = "filesystem", transport = McpTransport.STDIO, command = listOf("noop"))))
        }

        tools.map { it.name } shouldBe listOf("filesystem__read_file")
    }

    test("connect skips a server that fails to connect and still registers the rest") {
        val connector = mockk<McpConnector>()
        coEvery { connector.connect(match { it.name == "broken" }) } throws RuntimeException("boom")
        val workingSession = mockk<McpSession>()
        coEvery { workingSession.listTools() } returns listOf(RemoteToolInfo("ping", "pings", "{}"))
        coEvery { connector.connect(match { it.name == "ok" }) } returns workingSession
        val manager = McpClientManager(stdioConnector = connector, httpConnector = mockk())

        val tools = runBlocking {
            manager.connect(
                listOf(
                    McpServerConfig(name = "broken", transport = McpTransport.STDIO, command = listOf("x")),
                    McpServerConfig(name = "ok", transport = McpTransport.STDIO, command = listOf("y"))
                )
            )
        }

        tools.map { it.name } shouldBe listOf("ok__ping")
    }

    test("connect dispatches HTTP servers to the http connector") {
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns listOf(RemoteToolInfo("search", "searches docs", "{}"))
        val httpConnector = mockk<McpConnector>()
        coEvery { httpConnector.connect(any()) } returns session
        val manager = McpClientManager(stdioConnector = mockk(), httpConnector = httpConnector)

        val tools = runBlocking {
            manager.connect(listOf(McpServerConfig(name = "docs", transport = McpTransport.HTTP, url = "https://example.com/mcp")))
        }

        tools.map { it.name } shouldBe listOf("docs__search")
    }

    test("close closes every session opened by connect") {
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns emptyList()
        coJustRun { session.close() }
        val stdioConnector = mockk<McpConnector>()
        coEvery { stdioConnector.connect(any()) } returns session
        val manager = McpClientManager(stdioConnector = stdioConnector, httpConnector = mockk())

        runBlocking {
            manager.connect(listOf(McpServerConfig(name = "s", transport = McpTransport.STDIO, command = listOf("z"))))
        }
        manager.close()

        coVerify { session.close() }
    }
})
