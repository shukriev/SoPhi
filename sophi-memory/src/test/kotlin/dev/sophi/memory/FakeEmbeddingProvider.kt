package dev.sophi.memory

import dev.sophi.ai.api.EmbeddingProvider
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Deterministic offline embeddings for tests: each lowercase token hashes to one
 * dimension (+sign), vectors are L2-normalized — so texts sharing tokens have
 * higher cosine similarity, and identical texts embed identically.
 */
class FakeEmbeddingProvider(override val dimensions: Int = 64) : EmbeddingProvider {
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
        val v = FloatArray(dimensions)
        text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.forEach { token ->
            val h = token.hashCode()
            val idx = abs(h % dimensions)
            v[idx] += if ((h / dimensions) % 2 == 0) 1f else -1f
        }
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        v
    }
}
