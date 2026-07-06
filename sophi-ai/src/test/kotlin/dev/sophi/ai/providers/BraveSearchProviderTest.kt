package dev.sophi.ai.providers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class BraveSearchProviderTest : FunSpec({
    test("search() parses web results from the Brave API response") {
        val httpClient = mockk<HttpClient>()
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns 200
        every { response.body() } returns """
            {"web":{"results":[
                {"title":"Kotlin Docs","url":"https://kotlinlang.org","description":"The Kotlin language"}
            ]}}
        """.trimIndent()
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns response

        val provider = BraveSearchProvider(apiKey = "test-key", httpClient = httpClient)
        val results = provider.search("kotlin", 5)

        results shouldHaveSize 1
        results[0].title shouldBe "Kotlin Docs"
        results[0].url shouldBe "https://kotlinlang.org"
        results[0].snippet shouldBe "The Kotlin language"
    }

    test("search() returns an empty list when the web section is absent") {
        val httpClient = mockk<HttpClient>()
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns 200
        every { response.body() } returns "{}"
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns response

        val provider = BraveSearchProvider(apiKey = "test-key", httpClient = httpClient)
        provider.search("kotlin", 5) shouldHaveSize 0
    }

    test("search() throws when the API returns a non-200 status") {
        val httpClient = mockk<HttpClient>()
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns 401
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns response

        val provider = BraveSearchProvider(apiKey = "bad-key", httpClient = httpClient)
        io.kotest.assertions.throwables.shouldThrow<IllegalStateException> { provider.search("kotlin", 5) }
    }

    test("name defaults to brave") {
        BraveSearchProvider(apiKey = "test-key").name shouldBe "brave"
    }
})
