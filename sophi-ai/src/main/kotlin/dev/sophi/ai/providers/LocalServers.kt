package dev.sophi.ai.providers

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
        } catch (_: Exception) { /* not listening, or not an OpenAI-compatible server */ }
    }
    return null
}
