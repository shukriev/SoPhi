package dev.sophi.ai.providers

import com.openai.models.chat.completions.ChatCompletionChunk
import dev.sophi.ai.api.ToolCall

class ToolCallDeltaMerger {
    private class Accumulator {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private val byIndex = sortedMapOf<Long, Accumulator>()

    fun accumulate(delta: ChatCompletionChunk.Choice.Delta.ToolCall) {
        val acc = byIndex.getOrPut(delta.index()) { Accumulator() }
        delta.id().orElse(null)?.let { if (acc.id == null) acc.id = it }
        val function = delta.function().orElse(null)
        if (function != null) {
            function.name().orElse(null)?.let { if (acc.name == null) acc.name = it }
            function.arguments().orElse(null)?.let { acc.arguments.append(it) }
        }
    }

    fun build(): List<ToolCall> = byIndex.values.mapNotNull { acc ->
        val id = acc.id ?: return@mapNotNull null
        val name = acc.name ?: return@mapNotNull null
        ToolCall(id, name, acc.arguments.toString())
    }
}
