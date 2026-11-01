package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.mcp.config.McpServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class SlashMcpTest : FunSpec({
    fun mcpJson() = tempdir().toPath().resolve("mcp.json").also {
        Files.writeString(it, """{"servers":[{"name":"sentry","transport":"http","url":"https://mcp.sentry.dev/mcp"},{"name":"fs","transport":"stdio","command":["x"]}]}""")
    }

    test("/mcp login with an unknown server says so and opens nothing") {
        val logins = mutableListOf<McpServerConfig>()
        val msg = runBlocking { mcpAuth("login", "nope", mcpJson(), login = { logins += it; Result.success(emptyList()) }, logout = {}) }
        msg shouldBe "No MCP server named 'nope' in mcp.json."
        logins shouldBe emptyList()
    }

    test("/mcp login on a stdio server explains it takes secrets instead") {
        val msg = runBlocking { mcpAuth("login", "fs", mcpJson(), login = { error("not called") }, logout = {}) }
        msg shouldBe "fs runs locally; give it keys with \${secret:name} in its env instead."
    }

    test("/mcp login signs in and reports the tools it connected") {
        val msg = runBlocking { mcpAuth("login", "sentry", mcpJson(), login = { Result.success(listOf("sentry__a", "sentry__b")) }, logout = {}) }
        msg shouldBe "Signed in to sentry — 2 tools connected."
    }

    test("/mcp login reports a failed sign-in") {
        val msg = runBlocking { mcpAuth("login", "sentry", mcpJson(), login = { Result.failure(Exception("Sign-in timed out")) }, logout = {}) }
        msg shouldBe "Couldn't sign in to sentry: Sign-in timed out"
    }

    test("/mcp logout forgets the sign-in") {
        val out = mutableListOf<String>()
        runBlocking { mcpAuth("logout", "sentry", mcpJson(), login = { error("no") }, logout = { out += it }) } shouldBe "Signed out of sentry."
        out shouldBe listOf("sentry")
    }

    test("the slash command routes to the host's mcp action") {
        val out = mutableListOf<String>()
        val handler = SlashHandler(mockk<SessionManager>(relaxed = true), null, AgentConfig(model = "m"),
            mcp = { action, server -> "did $action $server" }) { out += it }
        runBlocking {
            handler.handle("/mcp login sentry", AgentSession(id = "s"))
            handler.handle("/mcp", AgentSession(id = "s"))
        }
        out shouldBe listOf("did login sentry", "Usage: /mcp login <server> | /mcp logout <server>")
    }
})
