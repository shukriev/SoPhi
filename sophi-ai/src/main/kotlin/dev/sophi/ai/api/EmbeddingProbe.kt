package dev.sophi.ai.api

import kotlinx.coroutines.delay

/**
 * Probes an embedding endpoint with one retry after [retryDelayMs] — local servers (e.g. Ollama)
 * can take longer than the request timeout to cold-swap a model into memory the first time, which
 * would otherwise disable memory for a whole session over a one-off delay rather than a real outage.
 */
suspend fun probeEmbeddingProvider(
    provider: EmbeddingProvider,
    retryDelayMs: Long = 3000
): Result<List<FloatArray>> {
    var result = runCatching { provider.embed(listOf("ping")) }
    if (result.isFailure) {
        delay(retryDelayMs)
        result = runCatching { provider.embed(listOf("ping")) }
    }
    return result
}
