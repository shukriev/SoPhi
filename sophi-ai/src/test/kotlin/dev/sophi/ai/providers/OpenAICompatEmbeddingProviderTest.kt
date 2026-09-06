package dev.sophi.ai.providers

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.time.Duration

class OpenAICompatEmbeddingProviderTest : FunSpec({
    test("embed() sends a real, non-empty request body and parses a real HTTP response") {
        // Regression test for a bug where java.net.http.HttpClient's default HTTP/2-preferring
        // behavior (it attempts a cleartext upgrade even over plain http://) could arrive at an
        // HTTP/1.1-only server with an empty body, surfacing as a generic "body: field required"
        // 400 with no indication the body was ever the problem. This exercises the real client
        // against a real (JDK-provided, HTTP/1.1-only) server rather than mocking the transport.
        var receivedBody: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/embeddings") { exchange ->
            receivedBody = exchange.requestBody.readBytes().decodeToString()
            val response = """{"data":[{"index":0,"embedding":[0.1,0.2]}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val provider = OpenAICompatEmbeddingProvider(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                apiKey = null,
                model = "test-model",
                dimensions = 2,
                requestTimeout = Duration.ofSeconds(5)
            )

            val result = runBlocking { provider.embed(listOf("ping")) }

            receivedBody shouldBe """{"model":"test-model","input":["ping"]}"""
            result.size shouldBe 1
            result[0].toList() shouldBe listOf(0.1f, 0.2f)
        } finally {
            server.stop(0)
        }
    }
    test("buildEmbeddingRequestBody encodes model and inputs") {
        val body = buildEmbeddingRequestBody("nomic-embed-text", listOf("hello", "world"))
        body shouldBe """{"model":"nomic-embed-text","input":["hello","world"]}"""
    }

    test("parseEmbeddingResponse returns vectors in index order") {
        val json = """
            {"object":"list","data":[
              {"object":"embedding","index":1,"embedding":[0.3,0.4]},
              {"object":"embedding","index":0,"embedding":[0.1,0.2]}
            ],"model":"m","usage":{"prompt_tokens":2,"total_tokens":2}}
        """.trimIndent()
        val vectors = parseEmbeddingResponse(json)
        vectors.size shouldBe 2
        vectors[0].toList() shouldBe listOf(0.1f, 0.2f)
        vectors[1].toList() shouldBe listOf(0.3f, 0.4f)
    }

    test("parseEmbeddingResponse rejects malformed payload") {
        shouldThrow<IllegalStateException> { parseEmbeddingResponse("""{"error":"nope"}""") }
    }
})
