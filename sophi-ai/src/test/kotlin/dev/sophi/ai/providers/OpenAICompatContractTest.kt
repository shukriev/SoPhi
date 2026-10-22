package dev.sophi.ai.providers

import com.sun.net.httpserver.HttpServer
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.ai.api.ToolDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

private val readFileTool = ToolDefinition("read_file", "Read a file", """{"type":"object","properties":{"path":{"type":"string"}}}""")

/** Streams the [fixture] from src/test/resources/contract through [type]'s provider; returns the events and the request body sent. */
private fun run(fixture: String, type: String = "openai-compat", tools: List<ToolDefinition> = emptyList()): Pair<List<StreamEvent>, String> {
    val body = StringBuilder()
    val sse = object {}.javaClass.getResource("/contract/$fixture")!!.readText()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/chat/completions") { ex ->
        body.append(ex.requestBody.readAllBytes().decodeToString())
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { it.write(sse.toByteArray()) }
    }
    server.start()
    try {
        val provider = buildProviderFromType(type, null, "http://127.0.0.1:${server.address.port}/v1", "m", maxRetries = 0)
        val events = runBlocking {
            provider.stream(CompletionRequest(messages = listOf(Message(MessageRole.USER, "hi")), model = "m", maxTokens = 50, tools = tools)).toList()
        }
        return events to body.toString()
    } finally {
        server.stop(0)
    }
}

class OpenAICompatContractTest : FunSpec({
    test("plain text streams content then finish") {
        val (events, _) = run("plain-text.sse")
        events shouldBe listOf(StreamEvent.Content("Hello"), StreamEvent.Content(", world"), StreamEvent.Finish("stop"))
    }

    test("native tool call is merged into one ToolCallsReady") {
        val (events, _) = run("native-tool-call.sse")
        events.filterIsInstance<StreamEvent.ToolCallsReady>() shouldBe
            listOf(StreamEvent.ToolCallsReady(listOf(ToolCall("call_1", "read_file", """{"path":"a.txt"}"""))))
    }

    test("tool call written into the text is recovered and hidden") {
        val (events, _) = run("hermes-text-tool-call.sse")
        val call = events.filterIsInstance<StreamEvent.ToolCallsReady>().single().calls.single()
        call.id shouldMatch Regex("[A-Za-z0-9]{9}")
        call.name shouldBe "read_file"
        call.argumentsJson shouldBe """{"path":"a.txt"}"""
        events.filterIsInstance<StreamEvent.Content>().forEach { it.text shouldNotContain "<tool_call>" }
    }

    test("think block split across chunks becomes reasoning") {
        val (events, _) = run("think-split.sse")
        events.filterIsInstance<StreamEvent.Reasoning>().joinToString("") { it.text } shouldBe "plan it"
        events.filterIsInstance<StreamEvent.Content>().joinToString("") { it.text } shouldBe "The answer."
    }

    test("local type sends parallel_tool_calls=false only when there are tools") {
        run("plain-text.sse", "local", listOf(readFileTool)).second shouldContain "\"parallel_tool_calls\":false"
        run("plain-text.sse", "local").second shouldNotContain "parallel_tool_calls"
        run("plain-text.sse", "openai-compat", listOf(readFileTool)).second shouldNotContain "parallel_tool_calls"
    }

    test("malformed arguments reach the caller unchanged") {
        val (events, _) = run("malformed-args.sse")
        events.filterIsInstance<StreamEvent.ToolCallsReady>().single().calls.single().argumentsJson shouldBe """{"path": "a.txt"""
    }
})
