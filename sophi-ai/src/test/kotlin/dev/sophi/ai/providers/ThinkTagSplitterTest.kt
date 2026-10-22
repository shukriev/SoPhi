package dev.sophi.ai.providers

import com.sun.net.httpserver.HttpServer
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

/** An OpenAI-compatible server that streams each of [contents] as one content delta. */
private fun contentServer(contents: List<String>): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/chat/completions") { ex ->
        ex.requestBody.readAllBytes()
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { out ->
            fun send(s: String) { out.write(s.toByteArray()); out.flush() }
            contents.forEach {
                send("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$it\"},\"finish_reason\":null}]}\n\n")
            }
            send("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
            send("data: [DONE]\n\n")
        }
    }
    server.start()
    return server
}

class ThinkTagSplitterTest : FunSpec({
    test("whole block in one chunk") {
        ThinkTagSplitter().feed("<think>plan</think>Hi") shouldBe Split("Hi", "plan")
    }

    test("markers split across chunks leak nothing") {
        val s = ThinkTagSplitter()
        val parts = listOf("<th", "ink>pl", "an</thi", "nk>Hi").map { s.feed(it) } + s.flush()
        parts.joinToString("") { it.content } shouldBe "Hi"
        parts.joinToString("") { it.reasoning } shouldBe "plan"
    }

    test("no think tags passes through unchanged") {
        val s = ThinkTagSplitter()
        val parts = listOf(s.feed("a < b"), s.flush())
        parts.joinToString("") { it.content } shouldBe "a < b"
        parts.joinToString("") { it.reasoning } shouldBe ""
    }

    test("unclosed think at end flushes as reasoning") {
        val s = ThinkTagSplitter()
        val parts = listOf(s.feed("<think>still</thi"), s.flush())
        parts.joinToString("") { it.reasoning } shouldBe "still</thi"
        parts.joinToString("") { it.content } shouldBe ""
    }

    test("partialSuffix") {
        partialSuffix("abc<thi", "<think>") shouldBe 4
        partialSuffix("abc", "<think>") shouldBe 0
    }

    test("a think block split across SSE chunks arrives as Reasoning, not Content") {
        val server = contentServer(listOf("<th", "ink>pl", "an</thi", "nk>Hi"))
        try {
            val provider = buildOpenAiCompatProvider(
                "http://127.0.0.1:${server.address.port}/v1", null, "m", maxRetries = 0,
            )
            val events = runBlocking {
                provider.stream(CompletionRequest(messages = listOf(Message(MessageRole.USER, "hi")), model = "m", maxTokens = 50)).toList()
            }
            events.filterIsInstance<StreamEvent.Content>().joinToString("") { it.text } shouldBe "Hi"
            events.filterIsInstance<StreamEvent.Reasoning>().joinToString("") { it.text } shouldBe "plan"
        } finally { server.stop(0) }
    }
})
