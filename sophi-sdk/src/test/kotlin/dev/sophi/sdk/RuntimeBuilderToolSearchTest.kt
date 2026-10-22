package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.McpSession
import dev.sophi.mcp.McpTool
import dev.sophi.mcp.RemoteToolInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
        build(manager, mcpJson("")).toolNames() shouldContain "tool_search"
    }

    test("no tool_search without deferred tools") {
        val manager = mockk<McpClientManager>(relaxed = true)
        coEvery { manager.connect(any(), any()) } returns tools(2, deferred = false)
        build(manager, mcpJson("")).toolNames() shouldNotContain "tool_search"
    }

    test("deferToolsOver from mcp.json reaches the manager") {
        val manager = mockk<McpClientManager>(relaxed = true)
        coEvery { manager.connect(any(), any()) } returns emptyList()
        build(manager, mcpJson(""""deferToolsOver":25,"""))
        coVerify { manager.connect(any(), 25) }
    }
})
