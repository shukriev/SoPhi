package dev.sophi.mcp.auth

import dev.sophi.core.credentials.CredentialStore
import dev.sophi.core.credentials.LOCAL_ACTOR
import dev.sophi.core.credentials.OAuthFlow
import dev.sophi.core.credentials.SecretMask
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.StreamableHttpMcpConnector
import dev.sophi.mcp.config.McpOAuthConfig
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private class MemoryStore : CredentialStore {
    val map = ConcurrentHashMap<String, String>()
    /** Each read is a `security` process with the real Keychain, so tests count them. */
    val reads = AtomicInteger()
    override fun get(name: String, actor: String) = map[if (actor == LOCAL_ACTOR) name else "$actor/$name"].also { reads.incrementAndGet() }
    override fun set(name: String, value: String, actor: String) { map[name] = value }
    override fun remove(name: String, actor: String) { map.remove(name) }
    override fun names(actor: String) = map.keys.sorted()
}

private fun form(body: String) = body.split("&").filter { '=' in it }
    .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }

private fun freePort() = ServerSocket(0).use { it.localPort }

/**
 * One process playing both sides: an MCP server at /mcp that wants a bearer token, and its authorization server.
 * Refresh tokens rotate: each one works once.
 */
private class AuthedMcpServer(
    val protectedResourceMetadata: Boolean = true,
    /** What the 401 names as its metadata, and the registration endpoint it advertises; null = its own. */
    val metadataUrl: String? = null,
    val registrationUrl: String? = null,
) : AutoCloseable {
    val port = freePort()
    val base = "http://127.0.0.1:$port"
    val url = "$base/mcp"
    val registrations = AtomicInteger()
    val tokenRequests = mutableListOf<Map<String, String>>()
    val validAccess = ConcurrentHashMap.newKeySet<String>()
    private val validRefresh = ConcurrentHashMap.newKeySet<String>()
    private val issued = AtomicInteger()
    /** Lets every access token expire at once, as if an hour went by. */
    fun expireAccess() = validAccess.clear()
    fun revokeRefresh() = validRefresh.clear()
    var echo = "pong"

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = port) {
        install(createApplicationPlugin("bearer") {
            onCall { call ->
                val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                if (call.request.path() == "/mcp" && (token == null || token !in validAccess)) {
                    call.response.header("WWW-Authenticate", "Bearer resource_metadata=\"${metadataUrl ?: "$base/.well-known/oauth-protected-resource"}\"")
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
        })
        routing {
            if (protectedResourceMetadata) get("/.well-known/oauth-protected-resource") {
                call.respondText("""{"resource":"$url","authorization_servers":["$base"]}""", ContentType.Application.Json)
            }
            get("/.well-known/oauth-authorization-server") {
                call.respondText(
                    """{"issuer":"$base","authorization_endpoint":"$base/authorize","token_endpoint":"$base/token","registration_endpoint":"${registrationUrl ?: "$base/register"}"}""",
                    ContentType.Application.Json,
                )
            }
            post("/register") {
                registrations.incrementAndGet()
                call.respondText("""{"client_id":"dyn-${registrations.get()}"}""", ContentType.Application.Json, HttpStatusCode.Created)
            }
            post("/token") {
                val f = form(call.receiveText())
                synchronized(tokenRequests) { tokenRequests += f }
                val ok = when (f["grant_type"]) {
                    "authorization_code" -> f["code"] == "good-code"
                    "refresh_token" -> validRefresh.remove(f["refresh_token"])
                    else -> false
                }
                if (!ok) { call.respondText("""{"error":"invalid_grant"}""", ContentType.Application.Json, HttpStatusCode.BadRequest); return@post }
                val n = issued.incrementAndGet()
                validAccess += "at-$n-0123456789"; validRefresh += "rt-$n-0123456789"
                call.respondText("""{"access_token":"at-$n-0123456789","refresh_token":"rt-$n-0123456789","expires_in":3600}""", ContentType.Application.Json)
            }
        }
        mcpStreamableHttp(path = "/mcp") {
            Server(Implementation("authed", "1.0.0"), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()))).apply {
                addTool(name = "ping", description = "Replies") { _ -> CallToolResult(content = listOf(TextContent(echo))) }
            }
        }
    }.start(wait = false)

    val config get() = McpServerConfig("sentry", McpTransport.HTTP, url = url)
    override fun close() { server.stop(100, 500) }
}

private val http = HttpClient.newHttpClient()

/** Plays the browser: answers the redirect with a good code and the request's own state. */
private class Browser {
    var opened = 0
    var lastAuthorize: Map<String, String> = emptyMap()
    val open: (URI) -> Unit = { uri ->
        opened++
        lastAuthorize = form(uri.rawQuery)
        val back = URI(lastAuthorize.getValue("redirect_uri") + "?code=good-code&state=" + lastAuthorize.getValue("state"))
        http.send(HttpRequest.newBuilder(back).GET().build(), HttpResponse.BodyHandlers.discarding())
    }
}

private class Rig(val store: MemoryStore = MemoryStore(), val lock: java.nio.file.Path = Files.createTempFile("sophi-cred", ".lock")) {
    val browser = Browser()
    val signInNeeded = mutableListOf<String>()
    val mask = SecretMask()
    val oauth = McpOAuth(store, mask, OAuthFlow(openBrowser = browser.open), lockFile = lock, onSignInNeeded = { signInNeeded += it })
    val manager = McpClientManager(httpConnector = StreamableHttpMcpConnector(auth = oauth), credentials = store, mask = mask)
    fun connect(config: McpServerConfig, failures: MutableList<Throwable> = mutableListOf()) =
        runBlocking { manager.connectOne(config, onFailure = { failures += it }) }
}

class McpOAuthTest : FunSpec({

    test("login discovers, registers dynamically and stores the sign-in") {
        AuthedMcpServer().use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            srv.registrations.get() shouldBe 1
            rig.store.map.getValue("mcp-oauth:sentry") shouldContain "rt-1-0123456789"
            srv.tokenRequests.single()["resource"] shouldBe srv.url
            rig.browser.lastAuthorize["resource"] shouldBe srv.url
            rig.connect(srv.config).map { it.name } shouldBe listOf("sentry__ping")
        }
    }

    test("login uses the configured client and never registers") {
        AuthedMcpServer().use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config.copy(oauth = McpOAuthConfig("gh-client", "gh-secret-123", listOf("repo")))) }.getOrThrow()
            srv.registrations.get() shouldBe 0
            rig.browser.lastAuthorize["client_id"] shouldBe "gh-client"
            rig.browser.lastAuthorize["scope"] shouldBe "repo"
            srv.tokenRequests.single()["client_secret"] shouldBe "gh-secret-123"
        }
    }

    test("falls back to the server origin when protected-resource metadata is missing") {
        AuthedMcpServer(protectedResourceMetadata = false).use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            rig.connect(srv.config).map { it.name } shouldBe listOf("sentry__ping")
        }
    }

    test("a taken stored port re-registers on a fresh one") {
        AuthedMcpServer().use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            val firstRedirect = rig.browser.lastAuthorize.getValue("redirect_uri")
            ServerSocket(URI(firstRedirect).port, 0, java.net.InetAddress.getByName("127.0.0.1")).use {
                runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            }
            srv.registrations.get() shouldBe 2
            (rig.browser.lastAuthorize.getValue("redirect_uri") != firstRedirect) shouldBe true
        }
    }

    test("a free stored port reuses the registration") {
        AuthedMcpServer().use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            srv.registrations.get() shouldBe 1
        }
    }

    test("401 mid-session refreshes once and retries") {
        AuthedMcpServer().use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            val tool = rig.connect(srv.config).single()
            srv.expireAccess()
            runBlocking { tool.execute("{}") } shouldBe "pong"
            srv.tokenRequests.count { it["grant_type"] == "refresh_token" } shouldBe 1
            rig.browser.opened shouldBe 1
        }
    }

    test("second refresher re-reads the rotated token under the lock") {
        AuthedMcpServer().use { srv ->
            val a = Rig()
            runBlocking { a.oauth.login(srv.config) }.getOrThrow()
            val b = Rig(store = a.store, lock = a.lock)
            val toolA = a.connect(srv.config).single()
            val toolB = b.connect(srv.config).single()
            srv.expireAccess()
            runBlocking { toolA.execute("{}") } shouldBe "pong"
            srv.expireAccess()
            runBlocking { toolB.execute("{}") } shouldBe "pong"
            a.signInNeeded + b.signInNeeded shouldBe emptyList()
        }
    }

    test("revoked refresh token fails the call with the sign-in message") {
        AuthedMcpServer().use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            val tool = rig.connect(srv.config).single()
            srv.expireAccess(); srv.revokeRefresh()
            runBlocking { tool.execute("{}") } shouldStartWith "Error: sentry needs you to sign in again"
            runBlocking { tool.execute("{}") } shouldStartWith "Error: sentry needs you to sign in again"
            rig.signInNeeded shouldBe listOf("sentry")
            rig.browser.opened shouldBe 1
        }
    }

    test("connect without a sign-in on a 401 server reports needs sign-in") {
        AuthedMcpServer().use { srv ->
            val rig = Rig()
            val failures = mutableListOf<Throwable>()
            rig.connect(srv.config, failures) shouldBe emptyList()
            failures.single().shouldBeInstanceOf<SignInRequiredException>().server shouldBe "sentry"
            rig.browser.opened shouldBe 0
        }
    }

    test("an echoed access token is masked") {
        AuthedMcpServer().use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            val tool = rig.connect(srv.config).single()
            srv.echo = "your token: at-1-0123456789"
            runBlocking { tool.execute("{}") } shouldBe "your token: [REDACTED:secret]"
        }
    }

    test("logout forgets the sign-in") {
        AuthedMcpServer().use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            rig.oauth.hasSignIn("sentry") shouldBe true
            rig.oauth.logout("sentry")
            rig.oauth.hasSignIn("sentry") shouldBe false
            rig.store.map.keys shouldBe emptySet()
        }
    }

    test("discovery refuses metadata on a non-https address instead of fetching it") {
        AuthedMcpServer(metadataUrl = "http://169.254.169.254/latest/meta-data").use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.exceptionOrNull()!!.message!! shouldContain "https"
            srv.registrations.get() shouldBe 0
            rig.browser.opened shouldBe 0
        }
    }

    test("registration refuses a non-https endpoint instead of posting to it") {
        AuthedMcpServer(registrationUrl = "http://10.0.0.5/register").use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.exceptionOrNull()!!.message!! shouldContain "https"
            rig.browser.opened shouldBe 0
        }
    }

    test("a remote server that needs no sign-in costs no Keychain read per request") {
        val port = freePort()
        val plain = dev.sophi.mcp.startTestMcpHttpServer(port)
        try {
            val rig = Rig()
            val tool = rig.connect(McpServerConfig("plain", McpTransport.HTTP, url = "http://127.0.0.1:$port/mcp")).single()
            val before = rig.store.reads.get()
            repeat(5) { runBlocking { tool.execute("{}") } shouldBe "pong" }
            rig.store.reads.get() shouldBe before
            repeat(3) { rig.oauth.hasSignIn("plain") }
            rig.store.reads.get() shouldBe before + 1
        } finally { plain.stop(100, 500) }
    }

    test("a revoked sign-in is refreshed once per call, not twice") {
        AuthedMcpServer().use { srv ->
            val rig = Rig()
            runBlocking { rig.oauth.login(srv.config) }.getOrThrow()
            val tool = rig.connect(srv.config).single()
            srv.expireAccess(); srv.revokeRefresh()
            runBlocking { tool.execute("{}") }
            val afterFirst = srv.tokenRequests.count { it["grant_type"] == "refresh_token" }
            runBlocking { tool.execute("{}") }
            srv.tokenRequests.count { it["grant_type"] == "refresh_token" } shouldBe afterFirst + 1
        }
    }

    test("a sign-in made by another process is picked up on the next 401") {
        AuthedMcpServer().use { srv ->
            val other = Rig()
            val rig = Rig(store = other.store, lock = other.lock)
            rig.connect(srv.config) shouldBe emptyList()
            runBlocking { other.oauth.login(srv.config) }.getOrThrow()
            rig.connect(srv.config).map { it.name } shouldBe listOf("sentry__ping")
        }
    }
})
