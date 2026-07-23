package dev.sophi.ai.providers

import com.openai.models.chat.completions.ChatCompletionChunk
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun toolCallDelta(
    index: Long,
    id: String? = null,
    name: String? = null,
    arguments: String? = null
): ChatCompletionChunk.Choice.Delta.ToolCall {
    val builder = ChatCompletionChunk.Choice.Delta.ToolCall.builder().index(index)
    id?.let { builder.id(it) }
    if (name != null || arguments != null) {
        val fnBuilder = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
        name?.let { fnBuilder.name(it) }
        arguments?.let { fnBuilder.arguments(it) }
        builder.function(fnBuilder.build())
    }
    return builder.build()
}

class ToolCallDeltaMergerTest : FunSpec({
    test("merges id, name (first fragment) and concatenated arguments across chunks for one call") {
        val merger = ToolCallDeltaMerger()
        merger.accumulate(toolCallDelta(index = 0, id = "call_1", name = "get_weather", arguments = "{\"city\":"))
        merger.accumulate(toolCallDelta(index = 0, arguments = "\"Paris\"}"))

        val result = merger.build()
        result shouldBe listOf(dev.sophi.ai.api.ToolCall("call_1", "get_weather", "{\"city\":\"Paris\"}"))
    }

    test("interleaved fragments from two concurrent tool calls merge independently by index") {
        val merger = ToolCallDeltaMerger()
        merger.accumulate(toolCallDelta(index = 0, id = "call_a", name = "tool_a", arguments = "{\"x\":"))
        merger.accumulate(toolCallDelta(index = 1, id = "call_b", name = "tool_b", arguments = "{\"y\":"))
        merger.accumulate(toolCallDelta(index = 0, arguments = "1}"))
        merger.accumulate(toolCallDelta(index = 1, arguments = "2}"))

        val result = merger.build()
        result shouldBe listOf(
            dev.sophi.ai.api.ToolCall("call_a", "tool_a", "{\"x\":1}"),
            dev.sophi.ai.api.ToolCall("call_b", "tool_b", "{\"y\":2}")
        )
    }

    test("a call whose id or name never arrived is omitted from the result") {
        val merger = ToolCallDeltaMerger()
        merger.accumulate(toolCallDelta(index = 0, arguments = "{}"))

        merger.build() shouldBe emptyList()
    }

    test("build() returns an empty list when nothing was accumulated") {
        ToolCallDeltaMerger().build() shouldBe emptyList()
    }
})
