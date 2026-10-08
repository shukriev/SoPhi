package dev.sophi.core.tools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private fun response(status: Int, body: String = "", location: String? = null, setCookie: String? = null): HttpResponse<String> {
    val r = mockk<HttpResponse<String>>()
    every { r.statusCode() } returns status
    every { r.body() } returns body
    val headers = buildMap {
        if (location != null) put("Location", listOf(location))
        if (setCookie != null) put("Set-Cookie", listOf(setCookie))
    }
    every { r.headers() } returns HttpHeaders.of(headers) { _, _ -> true }
    return r
}

class FetchUrlToolTest : FunSpec({

    test("execute() returns the status line and the response body for a public address") {
        val httpClient = mockk<HttpClient>()
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns response(200, "hello world")
        val tool = FetchUrlTool(httpClient)

        val result = runBlocking { tool.execute("""{"url":"http://93.184.216.34/page"}""") }

        result shouldBe "HTTP 200 http://93.184.216.34/page\n\nhello world"
    }

    test("execute() follows a relative redirect and says where it ended up") {
        val httpClient = mockk<HttpClient>()
        val sent = mutableListOf<HttpRequest>()
        every { httpClient.send(capture(sent), any<HttpResponse.BodyHandler<String>>()) } returnsMany listOf(
            response(303, location = "/final?x=1"), response(200, "landed")
        )
        val tool = FetchUrlTool(httpClient)

        val result = runBlocking { tool.execute("""{"url":"http://93.184.216.34/start"}""") }

        sent.map { it.uri().toString() } shouldBe listOf("http://93.184.216.34/start", "http://93.184.216.34/final?x=1")
        result shouldBe "HTTP 200 http://93.184.216.34/final?x=1 (redirected from http://93.184.216.34/start)\n\nlanded"
    }

    test("execute() refuses a redirect to a private address without requesting it") {
        val httpClient = mockk<HttpClient>()
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns
            response(302, location = "http://127.0.0.1:8080/admin")
        val tool = FetchUrlTool(httpClient)

        val result = runBlocking { tool.execute("""{"url":"http://93.184.216.34/start"}""") }

        result shouldContain "private/internal address"
        verify(exactly = 1) { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) }
    }

    test("execute() refuses a redirect to a non-http(s) scheme") {
        val httpClient = mockk<HttpClient>()
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns
            response(301, location = "file:///etc/passwd")
        val result = runBlocking { FetchUrlTool(httpClient).execute("""{"url":"http://93.184.216.34/start"}""") }
        result shouldContain "Error: redirect to a non-http(s) URL"
    }

    test("execute() stops after too many redirects") {
        val httpClient = mockk<HttpClient>()
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns
            response(302, location = "/again")
        val result = runBlocking { FetchUrlTool(httpClient).execute("""{"url":"http://93.184.216.34/loop"}""") }
        result shouldContain "Error: too many redirects"
        verify(exactly = 6) { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) }
    }

    test("cookies set during a redirect chain are sent back on the next hop (cookie-check redirect loops)") {
        val httpClient = mockk<HttpClient>()
        val sent = mutableListOf<HttpRequest>()
        every { httpClient.send(capture(sent), any<HttpResponse.BodyHandler<String>>()) } returnsMany listOf(
            response(302, location = "/check", setCookie = "session=abc; Path=/"), response(200, "ok")
        )
        val result = runBlocking { FetchUrlTool(httpClient).execute("""{"url":"http://93.184.216.34/start"}""") }
        sent[0].headers().firstValue("Cookie").isPresent shouldBe false
        sent[1].headers().firstValue("Cookie").orElse("") shouldContain "session=abc"
        result shouldContain "HTTP 200"
    }

    test("a cookie from one host is not sent to another host in the chain") {
        val httpClient = mockk<HttpClient>()
        val sent = mutableListOf<HttpRequest>()
        every { httpClient.send(capture(sent), any<HttpResponse.BodyHandler<String>>()) } returnsMany listOf(
            response(302, location = "http://93.184.216.35/elsewhere", setCookie = "session=abc; Path=/"), response(200, "ok")
        )
        runBlocking { FetchUrlTool(httpClient).execute("""{"url":"http://93.184.216.34/start"}""") }
        sent[1].headers().firstValue("Cookie").isPresent shouldBe false
    }

    test("cookies do not survive from one fetch_url call to the next") {
        val httpClient = mockk<HttpClient>()
        val sent = mutableListOf<HttpRequest>()
        every { httpClient.send(capture(sent), any<HttpResponse.BodyHandler<String>>()) } returnsMany listOf(
            response(200, "a", setCookie = "session=abc; Path=/"), response(200, "b")
        )
        val tool = FetchUrlTool(httpClient)
        runBlocking { tool.execute("""{"url":"http://93.184.216.34/one"}""") }
        runBlocking { tool.execute("""{"url":"http://93.184.216.34/two"}""") }
        sent[1].headers().firstValue("Cookie").isPresent shouldBe false
    }

    test("execute() shows a non-2xx status instead of a bare body") {
        val httpClient = mockk<HttpClient>()
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns response(404, "nope")
        val result = runBlocking { FetchUrlTool(httpClient).execute("""{"url":"http://93.184.216.34/missing"}""") }
        result shouldBe "HTTP 404 http://93.184.216.34/missing\n\nnope"
    }

    test("a redirect without a Location header is returned as-is") {
        val httpClient = mockk<HttpClient>()
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns response(302, "moved")
        val result = runBlocking { FetchUrlTool(httpClient).execute("""{"url":"http://93.184.216.34/x"}""") }
        result shouldBe "HTTP 302 http://93.184.216.34/x\n\nmoved"
    }

    test("execute() rejects a loopback URL without making a request") {
        val httpClient = mockk<HttpClient>()
        val tool = FetchUrlTool(httpClient)

        val result = runBlocking { tool.execute("""{"url":"http://127.0.0.1:8080/admin"}""") }

        result shouldContain "private/internal address"
        verify(exactly = 0) { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) }
    }

    test("execute() rejects a link-local metadata URL without making a request") {
        val httpClient = mockk<HttpClient>()
        val tool = FetchUrlTool(httpClient)

        val result = runBlocking { tool.execute("""{"url":"http://169.254.169.254/latest/meta-data"}""") }

        result shouldContain "private/internal address"
        verify(exactly = 0) { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) }
    }

    test("execute() throws IllegalArgumentException for a non-http(s) scheme") {
        val tool = FetchUrlTool(mockk())
        shouldThrow<IllegalArgumentException> {
            runBlocking { tool.execute("""{"url":"file:///etc/passwd"}""") }
        }
    }

    test("execute() truncates a response body larger than the cap") {
        val httpClient = mockk<HttpClient>()
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns response(200, "x".repeat(500_001))
        val tool = FetchUrlTool(httpClient)

        val result = runBlocking { tool.execute("""{"url":"http://93.184.216.34/big"}""") }

        result shouldContain "response truncated"
    }

    test("name is fetch_url") {
        FetchUrlTool(mockk()).name shouldBe "fetch_url"
    }

    test("riskLevel is DESTRUCTIVE") {
        FetchUrlTool(mockk()).riskLevel("{}") shouldBe RiskLevel.DESTRUCTIVE
    }
})
