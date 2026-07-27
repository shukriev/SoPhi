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
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class FetchUrlToolTest : FunSpec({

    test("execute() returns the response body for a public address") {
        val httpClient = mockk<HttpClient>()
        val response = mockk<HttpResponse<String>>()
        every { response.body() } returns "hello world"
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns response
        val tool = FetchUrlTool(httpClient)

        val result = runBlocking { tool.execute("""{"url":"http://93.184.216.34/page"}""") }

        result shouldBe "hello world"
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
        val response = mockk<HttpResponse<String>>()
        every { response.body() } returns "x".repeat(500_001)
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns response
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
