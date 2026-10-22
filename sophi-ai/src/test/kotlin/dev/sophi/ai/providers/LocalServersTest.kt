package dev.sophi.ai.providers

import com.sun.net.httpserver.HttpServer
import dev.sophi.ai.providers.LocalServerKind.LLAMA_CPP
import dev.sophi.ai.providers.LocalServerKind.LM_STUDIO
import dev.sophi.ai.providers.LocalServerKind.OLLAMA
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress

/** A server answering GET /v1/models with [status] and [body]; returns it and its base URL. */
private fun modelsServer(status: Int = 200, body: String): Pair<HttpServer, String> {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/models") { ex ->
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    return server to "http://127.0.0.1:${server.address.port}/v1"
}

private fun models(vararg ids: String) =
    """{"data":[${ids.joinToString(",") { "{\"id\":\"$it\"}" }}]}"""

private fun deadUrl(): String {
    val port = java.net.ServerSocket(0).use { it.localPort }
    return "http://127.0.0.1:$port/v1"
}

class LocalServersTest : FunSpec({
    test("first answering candidate wins, in order") {
        val (sa, a) = modelsServer(body = models("a1", "a2"))
        val (sb, b) = modelsServer(body = models("b1"))
        try {
            discoverLocalServer(listOf(LLAMA_CPP to deadUrl(), LM_STUDIO to a, OLLAMA to b)) shouldBe
                LocalServer(LM_STUDIO, a, listOf("a1", "a2"))
        } finally { sa.stop(0); sb.stop(0) }
    }

    test("nothing answering returns null") {
        discoverLocalServer(listOf(LLAMA_CPP to deadUrl())) shouldBe null
    }

    test("a non-200 or non-JSON answer is skipped") {
        val (s404, notFound) = modelsServer(404, "nope")
        val (sHtml, html) = modelsServer(body = "<html>hi</html>")
        val (sGood, good) = modelsServer(body = models("m"))
        try {
            discoverLocalServer(listOf(LLAMA_CPP to notFound, LM_STUDIO to html, OLLAMA to good)) shouldBe
                LocalServer(OLLAMA, good, listOf("m"))
        } finally { s404.stop(0); sHtml.stop(0); sGood.stop(0) }
    }

    test("default candidates follow the spec's order and ports") {
        defaultLocalCandidates().map { it.second } shouldBe listOf(
            "http://127.0.0.1:8080/v1", "http://127.0.0.1:1234/v1", "http://127.0.0.1:11434/v1")
    }
})
