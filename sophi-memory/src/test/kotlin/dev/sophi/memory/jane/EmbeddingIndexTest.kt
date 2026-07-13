// sophi-memory/src/test/kotlin/dev/sophi/memory/jane/EmbeddingIndexTest.kt
package dev.sophi.memory.jane

import dev.sophi.memory.FakeEmbeddingProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class EmbeddingIndexTest : FunSpec({
    test("cosine: identical vectors 1.0, orthogonal 0.0, zero-vector safe") {
        cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)) shouldBe (1.0 plusOrMinus 1e-9)
        cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)) shouldBe (0.0 plusOrMinus 1e-9)
        cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)) shouldBe 0.0
    }

    test("nearest returns k best by cosine, restricted to candidateIds when given") {
        val fake = FakeEmbeddingProvider()
        val vecs = kotlinx.coroutines.runBlocking {
            fake.embed(listOf("the cat sat on the mat", "the cat sat on a rug", "quarterly financial report"))
        }
        val index = EmbeddingIndex(mapOf("a" to vecs[0], "b" to vecs[1], "c" to vecs[2]))
        val query = kotlinx.coroutines.runBlocking { fake.embed(listOf("cat on the mat")) }[0]
        index.nearest(query, k = 2).map { it.id } shouldBe listOf("a", "b")
        index.nearest(query, k = 2, candidateIds = setOf("c")).map { it.id } shouldBe listOf("c")
    }

    test("put and remove mutate the index") {
        val index = EmbeddingIndex()
        index.put("x", floatArrayOf(1f, 0f))
        index.nearest(floatArrayOf(1f, 0f), 1).map { it.id } shouldBe listOf("x")
        index.remove("x")
        index.nearest(floatArrayOf(1f, 0f), 1) shouldBe emptyList()
    }

    test("fake embeddings are deterministic across instances") {
        val a = kotlinx.coroutines.runBlocking { FakeEmbeddingProvider().embed(listOf("hello world")) }[0]
        val b = kotlinx.coroutines.runBlocking { FakeEmbeddingProvider().embed(listOf("hello world")) }[0]
        a.toList() shouldBe b.toList()
    }
})
