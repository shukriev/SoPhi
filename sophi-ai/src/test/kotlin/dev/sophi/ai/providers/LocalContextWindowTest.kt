package dev.sophi.ai.providers

import com.sun.net.httpserver.HttpServer
import dev.sophi.ai.providers.LocalServerKind.LLAMA_CPP
import dev.sophi.ai.providers.LocalServerKind.LM_STUDIO
import dev.sophi.ai.providers.LocalServerKind.OLLAMA
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress

private fun fixture(name: String) =
    object {}.javaClass.getResource("/local-servers/$name")!!.readText()

/** Serves each path in [routes] with its body (any method); everything else is a 404. */
private fun serving(routes: Map<String, String>, block: (LocalServer) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    for ((path, body) in routes) server.createContext(path) { ex ->
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    try { block(LocalServer(null, "http://127.0.0.1:${server.address.port}/v1", emptyList())) }
    finally { server.stop(0) }
}

class LocalContextWindowTest : FunSpec({
    test("llama.cpp props → n_ctx") {
        serving(mapOf("/props" to fixture("llamacpp-props.json"))) {
            detectContextWindow(it.copy(kind = LLAMA_CPP), "qwen3-8b") shouldBe ContextWindow(16384)
        }
    }

    test("LM Studio picks the entry for the requested model") {
        serving(mapOf("/api/v0/models" to fixture("lmstudio-v0-models.json"))) {
            detectContextWindow(it.copy(kind = LM_STUDIO), "gemma-3-12b-it") shouldBe ContextWindow(65536)
            detectContextWindow(it.copy(kind = LM_STUDIO), "qwen3-8b") shouldBe ContextWindow(12288)
        }
    }

    test("Ollama num_ctx in parameters wins") {
        serving(mapOf("/api/show" to fixture("ollama-show-numctx.json"))) {
            detectContextWindow(it.copy(kind = OLLAMA), "qwen3:8b") shouldBe ContextWindow(32768)
        }
    }

    test("Ollama without num_ctx → 4096 with a warning") {
        serving(mapOf("/api/show" to fixture("ollama-show-default.json"))) {
            val w = detectContextWindow(it.copy(kind = OLLAMA), "qwen3:8b")!!
            w.tokens shouldBe 4096
            w.warning!! shouldContain "OLLAMA_CONTEXT_LENGTH=16384 ollama serve"
            w.warning!! shouldContain "tool definitions alone"
        }
    }

    test("unknown kind tries each endpoint") {
        serving(mapOf("/props" to fixture("llamacpp-props.json"))) {
            detectContextWindow(it, "qwen3-8b") shouldBe ContextWindow(16384)
        }
    }

    test("unknown kind: a 200 that isn't Ollama's /api/show shape is not read as Ollama's default") {
        // LM Studio answers unknown paths with 200 and an error body; that must not become a 4096 window.
        serving(mapOf("/" to """{"error":"Unexpected endpoint or method. Returning 200 anyway"}""")) {
            detectContextWindow(it, "qwen/qwen3-8b") shouldBe null
        }
    }

    test("a non-positive n_ctx is ignored") {
        serving(mapOf("/props" to """{"n_ctx":0}""")) {
            detectContextWindow(it.copy(kind = LLAMA_CPP), "m") shouldBe null
        }
    }

    test("nothing answers → null") {
        val port = java.net.ServerSocket(0).use { it.localPort }
        detectContextWindow(LocalServer(null, "http://127.0.0.1:$port/v1", emptyList()), "m") shouldBe null
    }
})
