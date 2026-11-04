package dev.sophi.ai.providers

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import java.net.InetSocketAddress

/** An Anthropic Messages endpoint that answers every request with the SSE [events] (name to data JSON). */
private fun anthropicServer(events: List<Pair<String, String>>): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/messages") { ex ->
        ex.requestBody.readAllBytes()
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { out ->
            events.forEach { (name, data) -> out.write("event: $name\ndata: $data\n\n".toByteArray()); out.flush() }
        }
    }
    server.start()
    return server
}

private const val START = """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-x","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":25,"output_tokens":1}}}"""
private const val END_DELTA = """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":40}}"""

private fun toolStart(index: Int, id: String, name: String) =
    "content_block_start" to """{"type":"content_block_start","index":$index,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}"""
private fun jsonDelta(index: Int, partial: String) =
    "content_block_delta" to """{"type":"content_block_delta","index":$index,"delta":{"type":"input_json_delta","partial_json":${ObjectMapper().writeValueAsString(partial)}}}"""
private fun stop(index: Int) = "content_block_stop" to """{"type":"content_block_stop","index":$index}"""

private suspend fun streamFrom(events: List<Pair<String, String>>): List<StreamEvent> {
    val server = anthropicServer(listOf("message_start" to START) + events +
        listOf("message_delta" to END_DELTA, "message_stop" to """{"type":"message_stop"}"""))
    try {
        val options = AnthropicChatOptions.builder()
            .baseUrl("http://127.0.0.1:${server.address.port}").apiKey("test").model("claude-x").maxTokens(100)
            .build()
        val provider = ClaudeProvider(AnthropicChatModel.builder().options(options).build())
        return provider.stream(CompletionRequest(
            messages = listOf(Message(MessageRole.USER, "weather in Paris?")), model = "claude-x", maxTokens = 100,
            tools = listOf(ToolDefinition("get_weather", "Weather", """{"type":"object"}"""))
        )).toList()
    } finally { server.stop(0) }
}

private fun List<StreamEvent>.calls() = filterIsInstance<StreamEvent.ToolCallsReady>().flatMap { it.calls }
private fun json(s: String) = ObjectMapper().readTree(s)

class ClaudeStreamToolCallTest : FunSpec({
    test("a streamed tool_use block comes out as a tool call with its arguments joined") {
        val calls = streamFrom(listOf(
            toolStart(0, "toolu_1", "get_weather"),
            jsonDelta(0, ""), jsonDelta(0, """{"locat"""), jsonDelta(0, """ion": "Par"""), jsonDelta(0, """is", "unit": "c"}"""),
            stop(0),
        )).calls()
        calls.map { it.id to it.name } shouldBe listOf("toolu_1" to "get_weather")
        json(calls[0].argumentsJson) shouldBe json("""{"location":"Paris","unit":"c"}""")
    }

    test("text before a tool call streams as content and the call still arrives") {
        val events = streamFrom(listOf(
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Checking."}}""",
            stop(0),
            toolStart(1, "toolu_1", "get_weather"), jsonDelta(1, """{"location":"""), jsonDelta(1, """"Paris"}"""), stop(1),
        ))
        events.filterIsInstance<StreamEvent.Content>().joinToString("") { it.text } shouldBe "Checking."
        events.calls().map { it.name } shouldBe listOf("get_weather")
    }

    test("two tool calls in one message both arrive, in order") {
        val calls = streamFrom(listOf(
            toolStart(0, "toolu_1", "get_weather"), jsonDelta(0, """{"location":"Pa"""), jsonDelta(0, """ris"}"""), stop(0),
            toolStart(1, "toolu_2", "get_weather"), jsonDelta(1, """{"location":"""), jsonDelta(1, """"Rome"}"""), stop(1),
        )).calls()
        calls.map { it.id } shouldBe listOf("toolu_1", "toolu_2")
        json(calls[1].argumentsJson) shouldBe json("""{"location":"Rome"}""")
    }

    test("a tool call with empty input comes out as {}") {
        val calls = streamFrom(listOf(toolStart(0, "toolu_1", "get_weather"), jsonDelta(0, ""), stop(0))).calls()
        calls.size shouldBe 1
        json(calls[0].argumentsJson) shouldBe json("{}")
    }
})
