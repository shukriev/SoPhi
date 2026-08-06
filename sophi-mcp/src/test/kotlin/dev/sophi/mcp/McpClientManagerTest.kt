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

    test("connect skips a server whose listTools() throws and still registers the rest, but keeps its session open") {
        val brokenSession = mockk<McpSession>()
        coEvery { brokenSession.listTools() } throws RuntimeException("discovery boom")
        coJustRun { brokenSession.close() }
        val workingSession = mockk<McpSession>()
        coEvery { workingSession.listTools() } returns listOf(RemoteToolInfo("ping", "pings", "{}"))
        coJustRun { workingSession.close() }

        val connector = mockk<McpConnector>()
        coEvery { connector.connect(match { it.name == "broken" }) } returns brokenSession
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

        // The broken session connected successfully — even though tool discovery on it failed,
        // it's still a real open session and must be closed alongside the rest.
        manager.close()
        coVerify { brokenSession.close() }
        coVerify { workingSession.close() }
    }

    test("close attempts every session even if an earlier session's close() throws") {
        val firstSession = mockk<McpSession>()
        coEvery { firstSession.listTools() } returns emptyList()
        coEvery { firstSession.close() } throws RuntimeException("close boom")
        val secondSession = mockk<McpSession>()
        coEvery { secondSession.listTools() } returns emptyList()
        coJustRun { secondSession.close() }

        val connector = mockk<McpConnector>()
        coEvery { connector.connect(match { it.name == "first" }) } returns firstSession
        coEvery { connector.connect(match { it.name == "second" }) } returns secondSession
        val manager = McpClientManager(stdioConnector = connector, httpConnector = mockk())

        runBlocking {
            manager.connect(
                listOf(
                    McpServerConfig(name = "first", transport = McpTransport.STDIO, command = listOf("x")),
                    McpServerConfig(name = "second", transport = McpTransport.STDIO, command = listOf("y"))
                )
            )
        }

        // Should not throw, and must still attempt to close the second session.
        manager.close()

        coVerify { firstSession.close() }
        coVerify { secondSession.close() }
    }

    test("connectOne registers namespaced tools for a single server, same as connect() for one config") {
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns listOf(RemoteToolInfo("read_file", "reads a file", "{}"))
        val stdioConnector = mockk<McpConnector>()
        coEvery { stdioConnector.connect(any()) } returns session
        val manager = McpClientManager(stdioConnector = stdioConnector, httpConnector = mockk())

        val tools = runBlocking {
            manager.connectOne(McpServerConfig(name = "filesystem", transport = McpTransport.STDIO, command = listOf("noop")))
        }

        tools.map { it.name } shouldBe listOf("filesystem__read_file")
    }

    test("disconnect closes and forgets a tracked server's session") {
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns listOf(RemoteToolInfo("ping", "pings", "{}"))
        coJustRun { session.close() }
        val stdioConnector = mockk<McpConnector>()
        coEvery { stdioConnector.connect(any()) } returns session
        val manager = McpClientManager(stdioConnector = stdioConnector, httpConnector = mockk())

        runBlocking {
            manager.connectOne(McpServerConfig(name = "s", transport = McpTransport.STDIO, command = listOf("z")))
            manager.disconnect("s")
        }

        coVerify { session.close() }
    }

    test("disconnect on an unknown server name is a no-op, does not throw") {
        val manager = McpClientManager(stdioConnector = mockk(), httpConnector = mockk())
        runBlocking { manager.disconnect("never-connected") }
    }

    test("disconnect swallows an exception from the session's close()") {
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns emptyList()
        coEvery { session.close() } throws RuntimeException("close boom")
        val stdioConnector = mockk<McpConnector>()
        coEvery { stdioConnector.connect(any()) } returns session
        val manager = McpClientManager(stdioConnector = stdioConnector, httpConnector = mockk())

        runBlocking {
            manager.connectOne(McpServerConfig(name = "s", transport = McpTransport.STDIO, command = listOf("z")))
            manager.disconnect("s")
        }
        // no assertion needed beyond "did not throw"
    }

    test("a disconnected server's session is not closed again by a later close()") {
        val session = mockk<McpSession>()
        coEvery { session.listTools() } returns emptyList()
        coJustRun { session.close() }
        val stdioConnector = mockk<McpConnector>()
        coEvery { stdioConnector.connect(any()) } returns session
        val manager = McpClientManager(stdioConnector = stdioConnector, httpConnector = mockk())

        runBlocking {
            manager.connectOne(McpServerConfig(name = "s", transport = McpTransport.STDIO, command = listOf("z")))
            manager.disconnect("s")
        }
        manager.close()

        coVerify(exactly = 1) { session.close() }
    }
})
