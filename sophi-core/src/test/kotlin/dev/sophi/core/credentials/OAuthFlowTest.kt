package dev.sophi.core.credentials

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.net.BindException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64

private fun query(s: String?) = (s ?: "").split("&").filter { '=' in it }
    .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }

/** An authorization server whose token endpoint answers with [tokenReply] (status, body) and records each form. */
private class FakeAuthServer(var tokenReply: Pair<Int, String> = 200 to """{"access_token":"at","refresh_token":"rt","expires_in":3600}""") : AutoCloseable {
    val forms = mutableListOf<Map<String, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/token") { ex ->
            forms += query(ex.requestBody.readAllBytes().decodeToString())
            val (code, body) = tokenReply
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val endpoints = OAuthEndpoints("http://127.0.0.1:${server.address.port}/authorize", "http://127.0.0.1:${server.address.port}/token")
    override fun close() = server.stop(0)
}

private val http = HttpClient.newHttpClient()

/** Plays the browser: records the authorize URL, then calls the redirect with [answer] built from its query. */
private class FakeBrowser(private val answer: (Map<String, String>) -> String = { q -> "code=c1&state=${q["state"]}" }) {
    var authorizeQuery: Map<String, String> = emptyMap()
    val open: (URI) -> Unit = { uri ->
        authorizeQuery = query(uri.rawQuery)
        val back = URI(authorizeQuery.getValue("redirect_uri") + "?" + answer(authorizeQuery))
        http.send(HttpRequest.newBuilder(back).GET().build(), HttpResponse.BodyHandlers.discarding())
    }
}

private fun s256(verifier: String) =
    Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

class OAuthFlowTest : FunSpec({

    test("authorize exchanges the code with the PKCE verifier") {
        FakeAuthServer().use { auth ->
            val browser = FakeBrowser()
            val flow = OAuthFlow(openBrowser = browser.open, nowMs = { 1_000L })
            val tokens = flow.loopback().use { lb -> runBlocking { flow.authorize(auth.endpoints, OAuthClient("cid"), lb, listOf("read")) } }
            tokens shouldBe Tokens("at", "rt", 1_000L + 3_600_000L)
            val form = auth.forms.single()
            form["grant_type"] shouldBe "authorization_code"
            form["code"] shouldBe "c1"
            s256(form.getValue("code_verifier")) shouldBe browser.authorizeQuery["code_challenge"]
            browser.authorizeQuery["code_challenge_method"] shouldBe "S256"
            browser.authorizeQuery["scope"] shouldBe "read"
            (form.getValue("code_verifier").length in 43..128) shouldBe true
        }
    }

    test("a wrong state never completes the sign-in") {
        FakeAuthServer().use { auth ->
            val flow = OAuthFlow(openBrowser = FakeBrowser { "code=c1&state=forged" }.open, callbackTimeoutMs = 300)
            val e = shouldThrow<OAuthException> {
                flow.loopback().use { lb -> runBlocking { flow.authorize(auth.endpoints, OAuthClient("cid"), lb, emptyList()) } }
            }
            e.message shouldBe "Sign-in timed out"
            auth.forms shouldBe emptyList()
        }
    }

    test("an error callback surfaces the server's description") {
        FakeAuthServer().use { auth ->
            val flow = OAuthFlow(openBrowser = FakeBrowser { q -> "error=access_denied&error_description=User+said+no&state=${q["state"]}" }.open)
            val e = shouldThrow<OAuthException> {
                flow.loopback().use { lb -> runBlocking { flow.authorize(auth.endpoints, OAuthClient("cid"), lb, emptyList()) } }
            }
            e.message!! shouldContain "User said no"
        }
    }

    test("extra params reach both the authorize URL and the token request") {
        FakeAuthServer().use { auth ->
            val browser = FakeBrowser()
            val flow = OAuthFlow(openBrowser = browser.open)
            val extra = mapOf("resource" to "https://mcp.example/mcp")
            flow.loopback().use { lb -> runBlocking { flow.authorize(auth.endpoints, OAuthClient("cid", "sec"), lb, emptyList(), extra) } }
            browser.authorizeQuery["resource"] shouldBe "https://mcp.example/mcp"
            auth.forms.single()["resource"] shouldBe "https://mcp.example/mcp"
            auth.forms.single()["client_secret"] shouldBe "sec"
        }
    }

    test("refresh keeps the old refresh token when none comes back") {
        FakeAuthServer(200 to """{"access_token":"at2","expires_in":60}""").use { auth ->
            val flow = OAuthFlow(openBrowser = {}, nowMs = { 0L })
            runBlocking { flow.refresh(auth.endpoints, OAuthClient("cid"), "rt-old") } shouldBe Tokens("at2", "rt-old", 60_000L)
            auth.forms.single()["grant_type"] shouldBe "refresh_token"
            auth.forms.single()["refresh_token"] shouldBe "rt-old"
        }
    }

    test("invalid_grant on refresh is OAuthException(\"refresh token rejected\")") {
        FakeAuthServer(400 to """{"error":"invalid_grant"}""").use { auth ->
            val e = shouldThrow<OAuthException> { runBlocking { OAuthFlow(openBrowser = {}).refresh(auth.endpoints, OAuthClient("cid"), "rt") } }
            e.message shouldBe "refresh token rejected"
        }
    }

    test("loopback on a taken port throws BindException") {
        val flow = OAuthFlow(openBrowser = {})
        flow.loopback().use { first ->
            val port = URI(first.redirectUri).port
            shouldThrow<BindException> { flow.loopback(port) }
        }
    }

    test("an authorize URL that isn't https (or loopback http) is refused before any browser opens") {
        var opened = 0
        val flow = OAuthFlow(openBrowser = { opened++ })
        listOf("file:///etc/passwd", "javascript:alert(1)", "http://evil.example/authorize", "x-app://do").forEach { url ->
            shouldThrow<OAuthException> {
                flow.loopback().use { lb -> runBlocking { flow.authorize(OAuthEndpoints(url, "https://a/token"), OAuthClient("c"), lb, emptyList()) } }
            }.message!! shouldContain "https"
        }
        opened shouldBe 0
    }

    test("a forged callback with the wrong state doesn't end a sign-in that then completes") {
        FakeAuthServer().use { auth ->
            val flow = OAuthFlow(openBrowser = { uri ->
                val q = query(uri.rawQuery)
                val cb = q.getValue("redirect_uri")
                http.send(HttpRequest.newBuilder(URI("$cb?code=evil&state=forged")).GET().build(), HttpResponse.BodyHandlers.discarding())
                http.send(HttpRequest.newBuilder(URI("$cb?code=c1&state=${q["state"]}")).GET().build(), HttpResponse.BodyHandlers.discarding())
            })
            val tokens = flow.loopback().use { lb -> runBlocking { flow.authorize(auth.endpoints, OAuthClient("cid"), lb, emptyList()) } }
            tokens.accessToken shouldBe "at"
            auth.forms.single()["code"] shouldBe "c1"
        }
    }
})
