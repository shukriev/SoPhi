package dev.sophi.ai.providers

import com.sun.net.httpserver.HttpServer
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.time.Duration

/** An OpenAI-compatible server that streams [chunks] tokens, [gapMs] apart, optionally going
 *  silent for [stallMs] after the first one. */
private fun sseServer(chunks: Int, gapMs: Long, stallMs: Long = 0): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/chat/completions") { ex ->
        ex.requestBody.readAllBytes()
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { out ->
            fun send(s: String) { out.write(s.toByteArray()); out.flush() }
            repeat(chunks) { i ->
                send("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"t$i \"},\"finish_reason\":null}]}\n\n")
                Thread.sleep(if (i == 0 && stallMs > 0) stallMs else gapMs)
            }
            send("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
            send("data: [DONE]\n\n")
        }
    }
    server.executor = java.util.concurrent.Executors.newCachedThreadPool()
    server.start()
    return server
}

private fun collect(server: HttpServer, timeout: Duration): String {
    val provider = buildOpenAiCompatProvider(
        "http://127.0.0.1:${server.address.port}/v1", null, "m", requestTimeout = timeout, maxRetries = 0,
    )
    val text = StringBuilder()
    runBlocking {
        provider.stream(CompletionRequest(messages = listOf(Message(MessageRole.USER, "hi")), model = "m", maxTokens = 50))
            .collect { if (it is StreamEvent.Content) text.append(it.text) }
    }
    return text.toString()
}

class OpenAICompatStreamTimeoutTest : FunSpec({
    test("a reply that keeps streaming is never cut off, however long it takes in total") {
        // Regression: requestTimeout used to cap the WHOLE call, so a local reasoning model's
        // long reply was killed mid-stream ("LLM stream error: Stream failed") while tokens
        // were still arriving. 12 chunks × 250 ms = 3 s, against a 1 s timeout.
        val server = sseServer(chunks = 12, gapMs = 250)
        try {
            collect(server, Duration.ofSeconds(1)) shouldBe (0 until 12).joinToString("") { "t$it " }
        } finally { server.stop(0) }
    }

    test("a stream that goes silent for longer than requestTimeout fails promptly") {
        val server = sseServer(chunks = 3, gapMs = 50, stallMs = 6_000)
        try {
            val started = System.currentTimeMillis()
            shouldThrowAny { collect(server, Duration.ofSeconds(1)) }
            (System.currentTimeMillis() - started).toInt() shouldBeLessThan 4_000
        } finally { server.stop(0) }
    }
})
