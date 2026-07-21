package dev.sophi.ai.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class CountingEmbeddingProvider(private val failFirstN: Int) : EmbeddingProvider {
    override val dimensions = 1
    var calls = 0
        private set

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        calls++
        if (calls <= failFirstN) error("endpoint unreachable")
        return texts.map { floatArrayOf(1f) }
    }
}

class EmbeddingProbeTest : FunSpec({
    test("succeeds on the first attempt without retrying") {
        val provider = CountingEmbeddingProvider(failFirstN = 0)
        val result = probeEmbeddingProvider(provider, retryDelayMs = 0)
        result.isSuccess shouldBe true
        provider.calls shouldBe 1
    }

    test("retries once after a delay and succeeds on the second attempt") {
        val provider = CountingEmbeddingProvider(failFirstN = 1)
        val result = probeEmbeddingProvider(provider, retryDelayMs = 0)
        result.isSuccess shouldBe true
        provider.calls shouldBe 2
    }

    test("fails when both the initial attempt and the retry fail") {
        val provider = CountingEmbeddingProvider(failFirstN = 99)
        val result = probeEmbeddingProvider(provider, retryDelayMs = 0)
        result.isFailure shouldBe true
        provider.calls shouldBe 2
    }
})
