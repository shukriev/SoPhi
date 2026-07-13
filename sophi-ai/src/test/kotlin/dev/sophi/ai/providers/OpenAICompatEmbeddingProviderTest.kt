package dev.sophi.ai.providers

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OpenAICompatEmbeddingProviderTest : FunSpec({
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
