package dev.sophi.mcp

import dev.sophi.core.credentials.CredentialStore
import dev.sophi.core.credentials.LOCAL_ACTOR
import dev.sophi.core.credentials.SecretMask
import dev.sophi.mcp.config.McpConfigLoader
import dev.sophi.mcp.config.McpOAuthConfig
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import dev.sophi.mcp.config.MissingSecretException
import dev.sophi.mcp.config.resolveSecrets
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

internal class MemoryStore(vararg entries: Pair<String, String>) : CredentialStore {
    val map = mutableMapOf(*entries)
    override fun get(name: String, actor: String) = map[if (actor == LOCAL_ACTOR) name else "$actor/$name"]
    override fun set(name: String, value: String, actor: String) { map[name] = value }
    override fun remove(name: String, actor: String) { map.remove(name) }
    override fun names(actor: String) = map.keys.sorted()
}

private class EchoSession(private val reply: String) : McpSession {
    override suspend fun listTools() = listOf(RemoteToolInfo("echo", "Echo", "{}"))
    override suspend fun callTool(name: String, argumentsJson: String) = reply
    override suspend fun close() {}
}

private class RecordingConnector(private val reply: String = "ok", private val fail: Exception? = null) : McpConnector {
    val seen = mutableListOf<McpServerConfig>()
    override suspend fun connect(config: McpServerConfig): McpSession {
        seen += config
        fail?.let { throw it }
        return EchoSession(reply)
    }
}

private fun stdio(name: String, env: Map<String, String>) = McpServerConfig(name, McpTransport.STDIO, command = listOf("x"), env = env)

class SecretRefsTest : FunSpec({

    test("env and header references resolve; plain values pass unchanged") {
        val store = MemoryStore("cf" to "tok-123456789", "gh" to "ghs-abcdefghij")
        val mask = SecretMask()
        val http = McpServerConfig(
            "r", McpTransport.HTTP, url = "https://mcp.example/mcp",
            headers = mapOf("Authorization" to "Bearer \${secret:cf}", "X-Plain" to "1"),
            oauth = McpOAuthConfig("client", clientSecret = "\${secret:gh}"),
        )
        val out = resolveSecrets(http, store, mask)
        out.headers shouldBe mapOf("Authorization" to "Bearer tok-123456789", "X-Plain" to "1")
        out.oauth?.clientSecret shouldBe "ghs-abcdefghij"
        resolveSecrets(stdio("s", mapOf("TOKEN" to "\${secret:cf}", "MODE" to "fast")), store, mask).env shouldBe
            mapOf("TOKEN" to "tok-123456789", "MODE" to "fast")
        mask.mask("ghs-abcdefghij") shouldBe "[REDACTED:secret]"
    }

    test("missing secret fails only that server") {
        val connector = RecordingConnector()
        val failures = mutableListOf<Throwable>()
        val manager = McpClientManager(stdioConnector = connector, credentials = MemoryStore())
        val tools = runBlocking {
            manager.connectOne(stdio("broken", mapOf("T" to "\${secret:x}")), onFailure = { failures += it }) +
                manager.connectOne(stdio("fine", mapOf("T" to "plain")))
        }
        tools.map { it.name } shouldBe listOf("fine__echo")
        failures.single().shouldBeInstanceOf<MissingSecretException>().secretName shouldBe "x"
        connector.seen.map { it.name } shouldBe listOf("fine")
    }

    test("echoed secret is masked in the tool result") {
        val manager = McpClientManager(stdioConnector = RecordingConnector(reply = "token is tok-123456789"), credentials = MemoryStore("cf" to "tok-123456789"))
        val tool = runBlocking { manager.connectOne(stdio("s", mapOf("T" to "\${secret:cf}"))) }.single()
        runBlocking { tool.execute("{}") } shouldBe "token is [REDACTED:secret]"
    }

    test("connect failure message is masked") {
        val failures = mutableListOf<Throwable>()
        val manager = McpClientManager(
            stdioConnector = RecordingConnector(fail = Exception("401 for tok-123456789")), credentials = MemoryStore("cf" to "tok-123456789"),
        )
        runBlocking { manager.connectOne(stdio("s", mapOf("T" to "\${secret:cf}")), onFailure = { failures += it }) }
        failures.single().message!! shouldContain "[REDACTED:secret]"
        failures.single().message!! shouldNotContain "tok-123456789"
    }

    test("old mcp.json without headers or oauth still loads") {
        val f = Files.createTempFile("mcp", ".json")
        Files.writeString(f, """{"servers":[{"name":"a","transport":"http","url":"https://x/mcp"},{"name":"b","transport":"stdio","command":["y"],"env":{"K":"v"}}]}""")
        val servers = McpConfigLoader().load(f).servers
        servers.map { it.headers } shouldBe listOf(emptyMap(), emptyMap())
        servers.map { it.oauth } shouldBe listOf(null, null)
    }
})
