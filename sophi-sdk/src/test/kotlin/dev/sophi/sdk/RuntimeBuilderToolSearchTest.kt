package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.McpSession
import dev.sophi.mcp.McpTool
import dev.sophi.mcp.RemoteToolInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

class RuntimeBuilderToolSearchTest : FunSpec({
    fun tools(n: Int, deferred: Boolean) = (1..n).map {
        McpTool(mockk<McpSession>(), "srv", RemoteToolInfo("t$it", "tool $it", "{}"), emptySet(), deferred = deferred)
    }

    fun mcpJson(body: String): Path =
        tempdir().toPath().resolve("mcp.json").also {
            Files.writeString(it, """{$body"servers":[{"name":"srv","transport":"stdio","command":["x"]}]}""")
        }

    fun build(manager: McpClientManager, config: Path) = RuntimeBuilder().apply {
        provider = mockk<LLMProvider>()
        sessionsDir = tempdir().toPath()
        model = "test-model"
    }.contextWindowTokens(100_000).mcpClientManager(manager).mcpConfig(config).build()

    test("the runtime registers tool_search when MCP tools are deferred") {
        val manager = mockk<McpClientManager>(relaxed = true)
        coEvery { manager.connect(any(), any()) } returns tools(11, deferred = true)
        val runtime = build(manager, mcpJson(""))
        runtime.toolNames() shouldContain "tool_search"
        val sent = runtime.toolRegistry.definitions().map { it.name }
        sent shouldContain "tool_search"
        sent.none { it.startsWith("srv__") } shouldBe true
    }

    test("no tool_search without deferred tools") {
        val manager = mockk<McpClientManager>(relaxed = true)
        coEvery { manager.connect(any(), any()) } returns tools(2, deferred = false)
        build(manager, mcpJson("")).toolNames() shouldNotContain "tool_search"
    }

    test("connectMcpServer registers tool_search once and passes the configured threshold") {
        val manager = mockk<McpClientManager>(relaxed = true)
        coEvery { manager.connect(any(), any()) } returns emptyList()
        coEvery { manager.connectOne(any(), any(), any()) } returnsMany listOf(tools(11, true), tools(11, true).map {
            McpTool(mockk<McpSession>(), "other", RemoteToolInfo(it.name.substringAfter("__"), "d", "{}"), emptySet(), deferred = true)
        })
        val runtime = build(manager, mcpJson(""""deferToolsOver":7,"""))
        val cfg = McpServerConfig("srv", McpTransport.STDIO, listOf("x"))
        runtime.toolNames() shouldNotContain "tool_search"

        runBlocking { runtime.connectMcpServer(cfg) }
        runtime.toolNames() shouldContain "tool_search"
        runBlocking { runtime.connectMcpServer(cfg.copy(name = "other")) }

        runtime.toolNames().count { it == "tool_search" } shouldBe 1
        runtime.toolRegistry.definitions().none { it.name.contains("__") } shouldBe true
        coVerify { manager.connectOne(any(), 7, any()) }
    }

    fun buildNoJson(manager: McpClientManager, over: Int?, config: Path? = null) = RuntimeBuilder().apply {
        provider = mockk<LLMProvider>()
        sessionsDir = tempdir().toPath()
        model = "test-model"
    }.contextWindowTokens(100_000).mcpClientManager(manager)
        .also { b -> over?.let { b.deferToolsOver(it) }; config?.let { b.mcpConfig(it) } }.build()

    test("builder deferToolsOver is used by connectMcpServer when there is no mcp.json") {
        val manager = mockk<McpClientManager>(relaxed = true)
        coEvery { manager.connect(any(), any()) } returns emptyList()
        coEvery { manager.connectOne(any(), any(), any()) } returns emptyList()
        val runtime = buildNoJson(manager, 7)
        runBlocking { runtime.connectMcpServer(McpServerConfig("srv", McpTransport.STDIO, listOf("x"))) }
        coVerify { manager.connectOne(any(), 7, any()) }
    }

    test("mcp.json deferToolsOver wins over the builder's") {
        val manager = mockk<McpClientManager>(relaxed = true)
        coEvery { manager.connect(any(), any()) } returns emptyList()
        coEvery { manager.connectOne(any(), any(), any()) } returns emptyList()
        val runtime = buildNoJson(manager, 7, mcpJson(""""deferToolsOver":25,"""))
        runBlocking { runtime.connectMcpServer(McpServerConfig("srv", McpTransport.STDIO, listOf("x"))) }
        coVerify { manager.connectOne(any(), 25, any()) }
    }

    test("deferToolsOver from mcp.json reaches the manager") {
        val manager = mockk<McpClientManager>(relaxed = true)
        coEvery { manager.connect(any(), any()) } returns emptyList()
        build(manager, mcpJson(""""deferToolsOver":25,"""))
        coVerify { manager.connect(any(), 25) }
    }
})
