package dev.sophi.ai.api

/**
 * Text-embedding provider. Implementations must return one vector per input text,
 * in input order, each of exactly [dimensions] length.
 */
interface EmbeddingProvider {
    val dimensions: Int
    suspend fun embed(texts: List<String>): List<FloatArray>
}
