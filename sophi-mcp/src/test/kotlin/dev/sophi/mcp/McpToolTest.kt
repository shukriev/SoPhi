package dev.sophi.mcp

import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

class McpToolTest : FunSpec({

    test("reach: safeTools read, hinted read, otherwise a change") {
        val session = mockk<McpSession>()
        McpTool(session, "okta", RemoteToolInfo("list_users", "", "{}"), setOf("list_users")).reach("{}") shouldBe Reach.OUTSIDE_READ
        McpTool(session, "okta", RemoteToolInfo("find_user", "", "{}", readOnlyHint = true), emptySet()).reach("{}") shouldBe Reach.OUTSIDE_HINTED_READ
        McpTool(session, "okta", RemoteToolInfo("deactivate_user", "", "{}"), emptySet()).reach("{}") shouldBe Reach.OUTSIDE_CHANGE
        // the hint never changes the approval tier
        McpTool(session, "okta", RemoteToolInfo("find_user", "", "{}", readOnlyHint = true), emptySet()).riskLevel("{}") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("effects: a user-vouched safe tool only reaches the network; anything else may also write, hint or not") {
        val session = mockk<McpSession>()
        val n = dev.sophi.core.tools.EffectKind.NETWORK
        val w = dev.sophi.core.tools.EffectKind.WRITES
        McpTool(session, "okta", RemoteToolInfo("list_users", "", "{}"), setOf("list_users")).effects("{}") shouldBe setOf(n)
        McpTool(session, "okta", RemoteToolInfo("find_user", "", "{}", readOnlyHint = true), emptySet()).effects("{}") shouldBe setOf(w, n)
        McpTool(session, "okta", RemoteToolInfo("deactivate_user", "", "{}"), emptySet()).effects("{}") shouldBe setOf(w, n)
    }

    test("name is namespaced with the server name") {
        val session = mockk<McpSession>()
        val tool = McpTool(session, "filesystem", RemoteToolInfo("read_file", "reads a file", "{}"), emptySet())
        tool.name shouldBe "filesystem__read_file"
    }

    test("riskLevel defaults to DESTRUCTIVE") {
        val session = mockk<McpSession>()
        val tool = McpTool(session, "filesystem", RemoteToolInfo("read_file", "reads a file", "{}"), emptySet())
        tool.riskLevel("{}") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("riskLevel is SAFE when the remote tool name is in safeTools") {
        val session = mockk<McpSession>()
        val tool = McpTool(
            session, "filesystem", RemoteToolInfo("list_directory", "lists a directory", "{}"), setOf("list_directory")
        )
        tool.riskLevel("{}") shouldBe RiskLevel.SAFE
    }

    test("execute delegates to session.callTool with the remote (non-namespaced) name") {
        val session = mockk<McpSession>()
        coEvery { session.callTool("read_file", """{"path":"a.txt"}""") } returns "file contents"
        val tool = McpTool(session, "filesystem", RemoteToolInfo("read_file", "reads a file", "{}"), emptySet())

        val result = runBlocking { tool.execute("""{"path":"a.txt"}""") }

        result shouldBe "file contents"
    }

    test("execute maps a session exception to an Error string") {
        val session = mockk<McpSession>()
        coEvery { session.callTool("read_file", any()) } throws RuntimeException("connection lost")
        val tool = McpTool(session, "filesystem", RemoteToolInfo("read_file", "reads a file", "{}"), emptySet())

        val result = runBlocking { tool.execute("{}") }

        result shouldBe "Error: connection lost"
    }

    test("execute times out and returns an Error string when the underlying call hangs past the configured timeout") {
        val session = mockk<McpSession>()
        coEvery { session.callTool("navigate", any()) } coAnswers {
            delay(10_000)
            "unreachable"
        }
        val tool = McpTool(
            session, "browser", RemoteToolInfo("navigate", "navigates", "{}"), emptySet(),
            timeout = 50.milliseconds
        )

        val result = runBlocking { tool.execute("{}") }

        result shouldBe "Error: MCP tool 'navigate' timed out after 50ms"
    }

    test("description and parametersJson are copied from the remote tool") {
        val session = mockk<McpSession>()
        val tool = McpTool(session, "filesystem", RemoteToolInfo("read_file", "reads a file", """{"type":"object"}"""), emptySet())
        tool.description shouldBe "reads a file"
        tool.parametersJson shouldBe """{"type":"object"}"""
    }
})
