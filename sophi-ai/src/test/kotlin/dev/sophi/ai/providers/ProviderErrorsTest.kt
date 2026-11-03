package dev.sophi.ai.providers

import com.sun.net.httpserver.HttpServer
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

/** A chat-completions server that answers every request with [status] and [body]; returns it and its base URL. */
private fun failingServer(status: Int, body: String): Pair<HttpServer, String> {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    return server to "http://127.0.0.1:${server.address.port}/v1"
}

private val REQUEST = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), model = "m")

/** What a real provider throws from a stream, and returns from complete, against [baseUrl]. */
private fun failures(baseUrl: String): List<Throwable> {
    val p = buildOpenAiCompatProvider(baseUrl, null, "m", maxRetries = 0)
    val streamed = runCatching { runBlocking { p.stream(REQUEST).collect() } }.exceptionOrNull()!!
    val completed = (runBlocking { p.complete(REQUEST) } as LLMResponse.Error).cause!!
    return listOf(streamed, completed)
}

private fun error(msg: String) = """{"error":{"message":"$msg","type":"x"}}"""

class ProviderErrorsTest : FunSpec({
    test("a server that isn't running is unavailable") {
        val port = java.net.ServerSocket(0).use { it.localPort }
        failures("http://127.0.0.1:$port/v1").forEach { unavailableReason(it).shouldNotBeNull() }
    }

    test("a 5xx and a model the server hasn't loaded are unavailable") {
        for ((status, body) in listOf(503 to error("Loading model"), 404 to error("model 'm' not found, try pulling it first"),
            400 to error("No models loaded. Please load a model."))) {
            val (server, url) = failingServer(status, body)
            try { failures(url).forEach { unavailableReason(it).shouldNotBeNull() } } finally { server.stop(0) }
        }
    }

    test("a bad request, a too-long prompt and a refused key are the request's fault") {
        for ((status, body) in listOf(400 to error("invalid tool schema"), 400 to error("context length exceeded"),
            401 to error("invalid api key"), 403 to error("forbidden"))) {
            val (server, url) = failingServer(status, body)
            try { failures(url).forEach { unavailableReason(it).shouldBeNull() } } finally { server.stop(0) }
        }
    }

    test("anything else is not an availability error") {
        unavailableReason(IllegalArgumentException("boom")).shouldBeNull()
    }
})
