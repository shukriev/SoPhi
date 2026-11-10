package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val FIXTURE = """
    <!doctype html><html lang="en"><head>
    <title>Acme Shop</title>
    <meta name="description" content="Things for sale">
    <meta name="csrf-token" content="CSRF-SECRET-123">
    <link rel="canonical" href="/shop">
    <link rel="stylesheet" href="/s.css">
    <script src="https://cdn.example/app.js"></script>
    <script>window.__STATE__ = {"sessionToken":"INLINE-SECRET"}; localStorage.setItem("k","STORED-SECRET")</script>
    </head><body data-user="DATA-SECRET">
    <h1>Shop</h1><h2>New</h2><h3>Shoes</h3>
    <a href="/about">About us</a>
    <a href="https://user:pw@other.example/p?token=LINK-SECRET&page=2">Next</a>
    <img src="/logo.png">
    <form method="post" action="/login">
      <input type="hidden" name="csrf_token" value="FORM-CSRF-SECRET">
      <input type="email" name="email" value="me@example.com" required>
      <input type="password" name="password" value="PASSWORD-SECRET">
      <textarea name="note">TEXTAREA-SECRET</textarea>
      <select name="plan"><option value="pro" selected>OPTION-SECRET</option></select>
    </form>
    </body></html>
""".trimIndent()

private fun page(body: String, headers: Map<String, List<String>> = emptyMap(), status: Int = 200, location: String? = null): HttpResponse<String> {
    val r = mockk<HttpResponse<String>>()
    every { r.statusCode() } returns status
    every { r.body() } returns body
    val all = headers + mapOf("Content-Type" to listOf("text/html; charset=utf-8")) + (location?.let { mapOf("Location" to listOf(it)) } ?: emptyMap())
    every { r.headers() } returns HttpHeaders.of(all) { _, _ -> true }
    return r
}

private fun inspect(vararg responses: HttpResponse<String>, url: String = "http://93.184.216.34/shop"): String {
    val client = mockk<HttpClient>()
    every { client.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returnsMany responses.toList()
    return runBlocking { WebInspectTool(client).execute("""{"url":"$url"}""") }
}

private val SECRET_HEADERS = mapOf(
    "Set-Cookie" to listOf("sid=COOKIE-SECRET; HttpOnly"),
    "WWW-Authenticate" to listOf("Bearer realm=AUTH-SECRET"),
    "X-Csrf-Token" to listOf("HEADER-CSRF-SECRET"),
    "Server" to listOf("nginx"),
)

class WebInspectToolTest : FunSpec({

    test("outlines a page: title, meta, headings, links, forms and resources") {
        val out = inspect(page(FIXTURE, mapOf("Server" to listOf("nginx"))))
        out shouldNotContain "builds itself with script"
        out shouldContain "HTTP 200 http://93.184.216.34/shop"
        out shouldContain "Title: Acme Shop"
        out shouldContain "lang: en"
        out shouldContain "canonical: http://93.184.216.34/shop"
        out shouldContain "description: Things for sale"
        out shouldContain "h1 Shop\n  h2 New\n    h3 Shoes"
        out shouldContain "http://93.184.216.34/about  About us"
        out shouldContain "form POST http://93.184.216.34/login"
        out shouldContain "email email required"
        out shouldContain "script https://cdn.example/app.js"
        out shouldContain "stylesheet http://93.184.216.34/s.css"
        out shouldContain "img http://93.184.216.34/logo.png"
        out shouldContain "inline scripts: 1"
        out shouldContain "server: nginx"
    }

    test("never shows form values, inline script, data attributes, secret meta, cookies or auth headers") {
        val out = inspect(page(FIXTURE, SECRET_HEADERS))
        listOf("FORM-CSRF-SECRET", "me@example.com", "PASSWORD-SECRET", "TEXTAREA-SECRET", "OPTION-SECRET", "INLINE-SECRET",
            "STORED-SECRET", "DATA-SECRET", "CSRF-SECRET-123", "COOKIE-SECRET", "AUTH-SECRET", "HEADER-CSRF-SECRET",
            "LINK-SECRET", "user:pw").forEach { out shouldNotContain it }
        out shouldContain "hidden csrf_token"
        out shouldContain "password password"
        out shouldContain "csrf-token: [redacted]"
        out shouldContain "https://other.example/p?token=[redacted]&page=2"
        out shouldContain "(3 headers withheld)"
    }

    test("shares fetch_url's redirect handling and private-address refusal") {
        val client = mockk<HttpClient>()
        every { client.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns
            page("", status = 302, location = "http://127.0.0.1/admin")
        runBlocking { WebInspectTool(client).execute("""{"url":"http://93.184.216.34/start"}""") } shouldContain "private/internal address"
        verify(exactly = 1) { client.send(any(), any<HttpResponse.BodyHandler<String>>()) }
    }

    test("a page with scripts but no text says it is a static view") {
        inspect(page("""<html><body><div id="root"></div><script src="/a.js"></script></body></html>""")) shouldContain
            "builds itself with script"
    }

    test("is deferred, read-only in kind: network effect, asks like fetch_url, outside text") {
        val tool = WebInspectTool()
        tool.name shouldBe "web_inspect"
        tool.deferred shouldBe true
        tool.outsideText shouldBe true
        tool.effects("""{"url":"https://a.example"}""") shouldBe setOf(EffectKind.NETWORK)
        tool.riskLevel("""{"url":"https://a.example"}""") shouldBe RiskLevel.DESTRUCTIVE
        tool.permissionSubjects("""{"url":"https://A.example/x"}""")!!.single().text shouldBe "https://a.example/x"
    }
})
