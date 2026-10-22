package dev.sophi.ai.providers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val mapper = ObjectMapper()

enum class LocalServerKind(val defaultPort: Int) { LLAMA_CPP(8080), LM_STUDIO(1234), OLLAMA(11434) }

/** A reachable OpenAI-compatible server. [kind] is null when the user supplied the URL. */
data class LocalServer(val kind: LocalServerKind?, val baseUrl: String, val models: List<String>)

const val NO_LOCAL_SERVER_MESSAGE: String =
    "No local model server found on :8080 (llama.cpp), :1234 (LM Studio) or :11434 (Ollama). " +
        "Start one (e.g. `ollama serve` then `ollama pull qwen3:8b`), pass --base-url, " +
        "or use --provider claude --model <name> with ANTHROPIC_API_KEY set."

fun defaultLocalCandidates(host: String = "127.0.0.1"): List<Pair<LocalServerKind, String>> =
    LocalServerKind.entries.map { it to "http://$host:${it.defaultPort}/v1" }

/** First candidate whose `GET {baseUrl}/models` answers 200 with a `data` array, else null. */
fun discoverLocalServer(
    candidates: List<Pair<LocalServerKind?, String>> = defaultLocalCandidates(),
    probeTimeout: Duration = Duration.ofMillis(500),
): LocalServer? {
    val http = HttpClient.newBuilder().connectTimeout(probeTimeout).build()
    for ((kind, baseUrl) in candidates) {
        try {
            val req = HttpRequest.newBuilder(URI.create("$baseUrl/models")).timeout(probeTimeout).GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) continue
            val data = mapper.readTree(res.body())?.get("data")?.takeIf { it.isArray } ?: continue
            return LocalServer(kind, baseUrl, data.mapNotNull { it.get("id")?.asText() })
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) { /* not listening, or not an OpenAI-compatible server */ }
    }
    return null
}

/** Ollama serves 4096 tokens unless num_ctx is set, far below what an agent session needs. */
const val OLLAMA_DEFAULT_CTX = 4096

data class ContextWindow(val tokens: Int, val warning: String? = null)

/**
 * The context window [server] actually loaded [model] with (not the model's trained maximum), or null
 * when it can't be read. With an unknown [LocalServer.kind], tries llama.cpp, LM Studio, then Ollama.
 */
fun detectContextWindow(server: LocalServer, model: String, timeout: Duration = Duration.ofSeconds(2)): ContextWindow? {
    val root = server.baseUrl.removeSuffix("/v1")
    val http = HttpClient.newBuilder().connectTimeout(timeout).build()
    fun call(path: String, body: String? = null): JsonNode? {
        val b = HttpRequest.newBuilder(URI.create(root + path)).timeout(timeout)
        val req = if (body == null) b.GET() else b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        val res = http.send(req.build(), HttpResponse.BodyHandlers.ofString())
        return if (res.statusCode() == 200) mapper.readTree(res.body()) else null
    }
    fun JsonNode?.window() = this?.takeIf { it.canConvertToInt() }?.let { ContextWindow(it.asInt()) }
    fun llamaCpp() = call("/props")?.let { it.get("n_ctx") ?: it.path("default_generation_settings").get("n_ctx") }.window()
    fun lmStudio() = call("/api/v0/models")?.path("data")?.firstOrNull { it.path("id").asText() == model }
        ?.get("loaded_context_length").window()
    fun ollama(): ContextWindow? {
        val show = call("/api/show", mapper.writeValueAsString(mapOf("model" to model))) ?: return null
        val numCtx = Regex("""(?m)^\s*num_ctx\s+(\d+)""").find(show.path("parameters").asText())?.groupValues?.get(1)?.toIntOrNull()
        return if (numCtx != null) ContextWindow(numCtx) else ContextWindow(
            OLLAMA_DEFAULT_CTX,
            "Ollama is running $model with its default 4096-token window; set num_ctx (e.g. OLLAMA_CONTEXT_LENGTH=32768) for longer sessions.",
        )
    }
    val probes = when (server.kind) {
        LocalServerKind.LLAMA_CPP -> listOf(::llamaCpp)
        LocalServerKind.LM_STUDIO -> listOf(::lmStudio)
        LocalServerKind.OLLAMA -> listOf(::ollama)
        null -> listOf(::llamaCpp, ::lmStudio, ::ollama)
    }
    for (probe in probes) {
        try {
            probe()?.let { return it }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) { /* not listening, or an unexpected shape: try the next */ }
    }
    return null
}
