package dev.sophi.ai.providers

import dev.sophi.ai.api.EmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
private data class EmbeddingRequestBody(val model: String, val input: List<String>)

@Serializable
private data class EmbeddingDatum(val index: Int, val embedding: List<Float>)

@Serializable
private data class EmbeddingResponseBody(val data: List<EmbeddingDatum> = emptyList())

private val json = Json { ignoreUnknownKeys = true }

internal fun buildEmbeddingRequestBody(model: String, texts: List<String>): String =
    json.encodeToString(EmbeddingRequestBody.serializer(), EmbeddingRequestBody(model, texts))

internal fun parseEmbeddingResponse(body: String): List<FloatArray> {
    val parsed = runCatching { json.decodeFromString(EmbeddingResponseBody.serializer(), body) }
        .getOrElse { throw IllegalStateException("Malformed embeddings response: ${it.message}") }
    check(parsed.data.isNotEmpty()) { "Malformed embeddings response: no data array" }
    return parsed.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
}

/**
 * Embeddings via the OpenAI-compatible POST /v1/embeddings endpoint — works for OpenAI,
 * Ollama (http://localhost:11434/v1), and vLLM. [apiKey] = null omits the Authorization
 * header, which is what local servers expect.
 */
class OpenAICompatEmbeddingProvider(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val dimensions: Int,
    requestTimeout: Duration
) : EmbeddingProvider {
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(requestTimeout).build()
    private val timeout = requestTimeout

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.trimEnd('/') + "/embeddings"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .apply { if (apiKey != null) header("Authorization", "Bearer $apiKey") }
            .POST(HttpRequest.BodyPublishers.ofString(buildEmbeddingRequestBody(model, texts)))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Embeddings request failed: HTTP ${response.statusCode()} ${response.body().take(200)}"
        }
        val vectors = parseEmbeddingResponse(response.body())
        check(vectors.size == texts.size) { "Expected ${texts.size} embeddings, got ${vectors.size}" }
        vectors.forEach { check(it.size == dimensions) { "Expected $dimensions dims, got ${it.size}" } }
        vectors
    }
}

fun buildOpenAiCompatEmbeddingProvider(
    baseUrl: String,
    apiKey: String?,
    model: String,
    dimensions: Int = 1536,
    requestTimeout: Duration = Duration.ofSeconds(30)
): EmbeddingProvider = OpenAICompatEmbeddingProvider(baseUrl, apiKey, model, dimensions, requestTimeout)
