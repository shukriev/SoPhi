package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.credentials.CredentialStore
import dev.sophi.core.credentials.LOCAL_ACTOR
import dev.sophi.mcp.McpConnector
import dev.sophi.mcp.McpSession
import dev.sophi.mcp.RemoteToolInfo
import dev.sophi.mcp.auth.McpOAuth
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

private class Store(vararg e: Pair<String, String>) : CredentialStore {
    val map = mutableMapOf(*e)
    override fun get(name: String, actor: String) = map[if (actor == LOCAL_ACTOR) name else "$actor/$name"]
    override fun set(name: String, value: String, actor: String) { map[name] = value }
    override fun remove(name: String, actor: String) { map.remove(name) }
    override fun names(actor: String) = map.keys.sorted()
}

/** Connects every server to a one-tool session and remembers the config it was given. */
private class Recorder : McpConnector {
    val seen = mutableListOf<McpServerConfig>()
    override suspend fun connect(config: McpServerConfig): McpSession {
        seen += config
        return object : McpSession {
            override suspend fun listTools() = listOf(RemoteToolInfo("t", "t", "{}"))
            override suspend fun callTool(name: String, argumentsJson: String) = "ok"
            override suspend fun close() {}
        }
    }
}

class RuntimeBuilderCredentialsTest : FunSpec({
    fun mcpJson(servers: String): Path = tempdir().toPath().resolve("mcp.json").also { Files.writeString(it, """{"servers":[$servers]}""") }

    fun builder(stdio: McpConnector, http: McpConnector = Recorder(), oauth: McpOAuth? = null) = RuntimeBuilder().apply {
        provider = mockk<LLMProvider>()
        sessionsDir = tempdir().toPath()
        model = "test-model"
    }.contextWindowTokens(100_000).also { it.mcpWiring(stdio, http, oauth) }

    test("a stdio server's env secret resolves from the configured store") {
        val stdio = Recorder()
        val runtime = builder(stdio).credentials(Store("cf" to "tok-123456789"))
            .mcpConfig(mcpJson("""{"name":"cf","transport":"stdio","command":["x"],"env":{"T":"${'$'}{secret:cf}"}}""")).build()
        stdio.seen.single().env shouldBe mapOf("T" to "tok-123456789")
        runtime.toolRegistry.names().contains("cf__t") shouldBe true
    }

    test("without credentials() a secret reference fails that server and others connect") {
        val stdio = Recorder()
        val runtime = builder(stdio).mcpConfig(mcpJson(
            """{"name":"a","transport":"stdio","command":["x"],"env":{"T":"${'$'}{secret:cf}"}},{"name":"b","transport":"stdio","command":["y"]}""",
        )).build()
        stdio.seen.map { it.name } shouldBe listOf("b")
        runtime.toolRegistry.names().filter { "__" in it } shouldBe listOf("b__t")
    }

    test("mcpLogin reconnects the server and returns its tools") {
        val http = Recorder()
        val oauth = mockk<McpOAuth>(relaxed = true)
        coEvery { oauth.login(any(), any()) } returns Result.success(Unit)
        val runtime = builder(Recorder(), http, oauth).credentials(Store()).mcpConfig(mcpJson("")).build()
        val config = McpServerConfig("sentry", McpTransport.HTTP, url = "https://mcp.sentry.dev/mcp")
        runBlocking { runtime.mcpLogin(config) {} }.getOrThrow() shouldBe listOf("sentry__t")
        http.seen.single().name shouldBe "sentry"
    }

    test("mcpLogin without credentials() says so") {
        val runtime = builder(Recorder()).mcpConfig(mcpJson("")).build()
        val config = McpServerConfig("sentry", McpTransport.HTTP, url = "https://mcp.sentry.dev/mcp")
        runBlocking { runtime.mcpLogin(config) {} }.exceptionOrNull()!!.message!! shouldContain "credentials()"
    }
})
