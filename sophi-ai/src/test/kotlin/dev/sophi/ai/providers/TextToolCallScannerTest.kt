package dev.sophi.ai.providers

import com.sun.net.httpserver.HttpServer
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

/** Streams each of [contents] (already JSON-escaped) as one content delta; sends finish_reason only if [finish]. */
private fun textServer(contents: List<String>, finish: Boolean): HttpServer {
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
            if (finish) send("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
            send("data: [DONE]\n\n")
        }
    }
    server.start()
    return server
}

private fun streamEvents(server: HttpServer): List<StreamEvent> {
    val provider = buildOpenAiCompatProvider("http://127.0.0.1:${server.address.port}/v1", null, "m", maxRetries = 0)
    return runBlocking {
        provider.stream(CompletionRequest(messages = listOf(Message(MessageRole.USER, "hi")), model = "m", maxTokens = 50)).toList()
    }
}

class TextToolCallScannerTest : FunSpec({
    val hermes = "<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}</tool_call>"

    test("hermes block becomes a call and is not shown") {
        val sc = TextToolCallScanner()
        sc.feed("Let me look.$hermes") shouldBe "Let me look."
        val call = sc.finish().calls.single()
        // Mistral chat templates reject any id that isn't exactly 9 alphanumerics.
        call.id shouldMatch Regex("[A-Za-z0-9]{9}")
        call shouldBe ToolCall(call.id, "read_file", "{\"path\":\"a.txt\"}")
    }

    test("two hermes blocks become two calls") {
        val sc = TextToolCallScanner()
        sc.feed("$hermes\n$hermes") shouldBe ""
        val ids = sc.finish().calls.map { it.id }
        ids.size shouldBe 2
        ids.toSet().size shouldBe 2
    }

    test("two scanners use different ids") {
        fun id() = TextToolCallScanner().apply { feed(hermes) }.finish().calls.single().id
        id() shouldNotBe id()
    }

    test("mistral array plus trailing text gives leftover, no call") {
        val text = "[TOOL_CALLS][{\"name\":\"glob\",\"arguments\":{}}] Done, now I'll summarise."
        val sc = TextToolCallScanner().apply { feed(text) }
        sc.finish() shouldBe Result(emptyList(), text)
    }

    test("hermes block with junk after the JSON gives leftover, no call") {
        val text = "<tool_call>{\"name\":\"x\"} junk</tool_call>"
        val sc = TextToolCallScanner().apply { feed(text) }
        sc.finish() shouldBe Result(emptyList(), text)
    }

    test("mistral array") {
        val sc = TextToolCallScanner()
        sc.feed("[TOOL_CALLS][{\"name\":\"glob\",\"arguments\":{\"pattern\":\"*.kt\"}}]")
        sc.finish().calls.single().name shouldBe "glob"
    }

    test("marker split across chunks") {
        val sc = TextToolCallScanner()
        val shown = listOf("<tool_", "call>{\"name\":\"x\",\"arguments\":{}}</tool", "_call>").joinToString("") { sc.feed(it) }
        shown shouldBe ""
        val call = sc.finish().calls.single()
        listOf(call) shouldBe listOf(ToolCall(call.id, "x", "{}"))
    }

    test("broken JSON inside the block is returned as content, no call") {
        val sc = TextToolCallScanner()
        sc.feed("<tool_call>{\"name\":\"x\",</tool_call>")
        sc.finish() shouldBe Result(emptyList(), "<tool_call>{\"name\":\"x\",</tool_call>")
    }

    test("unclosed block and missing name are returned as content") {
        val a = TextToolCallScanner().apply { feed("<tool_call>{\"name\":\"x\"}") }
        a.finish() shouldBe Result(emptyList(), "<tool_call>{\"name\":\"x\"}")
        val b = TextToolCallScanner().apply { feed("<tool_call>{\"arguments\":{}}</tool_call>") }
        b.finish().calls shouldBe emptyList()
    }

    test("free text mentioning a tool is not a call") {
        val sc = TextToolCallScanner()
        sc.feed("I will call read_file on a.txt") shouldBe "I will call read_file on a.txt"
        sc.finish().calls shouldBe emptyList()
    }

    test("a held partial marker comes back as leftover") {
        val sc = TextToolCallScanner()
        sc.feed("a <tool_") shouldBe "a "
        sc.finish() shouldBe Result(emptyList(), "<tool_")
    }

    test("a hermes call streamed over several SSE chunks arrives as ToolCallsReady") {
        val server = textServer(
            listOf("Looking.<tool_", "call>{\\\"name\\\":\\\"read_file\\\",", "\\\"arguments\\\":{\\\"path\\\":\\\"a.txt\\\"}}</tool_", "call>"),
            finish = true,
        )
        try {
            val events = streamEvents(server)
            events.filterIsInstance<StreamEvent.Content>().joinToString("") { it.text } shouldBe "Looking."
            events.filterIsInstance<StreamEvent.Content>().forEach { it.text shouldNotContain "<tool_call>" }
            val c = events.filterIsInstance<StreamEvent.ToolCallsReady>().single().calls.single()
            c shouldBe ToolCall(c.id, "read_file", "{\"path\":\"a.txt\"}")
        } finally { server.stop(0) }
    }

    test("a stream without finish_reason still delivers held text") {
        val server = textServer(listOf("hello <tool_"), finish = false)
        try {
            streamEvents(server).filterIsInstance<StreamEvent.Content>().joinToString("") { it.text } shouldBe "hello <tool_"
        } finally { server.stop(0) }
    }

    test("a stream without finish_reason still delivers a complete text call") {
        val server = textServer(listOf("<tool_call>{\\\"name\\\":\\\"x\\\",\\\"arguments\\\":{}}</tool_call>"), finish = false)
        try {
            streamEvents(server).filterIsInstance<StreamEvent.ToolCallsReady>().single().calls.single().name shouldBe "x"
        } finally { server.stop(0) }
    }
})
