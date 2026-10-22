package dev.sophi.ai.providers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.sophi.ai.api.ToolCall

internal data class Result(val calls: List<ToolCall>, val leftoverContent: String)

private const val HERMES_OPEN = "<tool_call>"
private const val HERMES_CLOSE = "</tool_call>"
private const val MISTRAL = "[TOOL_CALLS]"
private val markers = listOf(HERMES_OPEN, MISTRAL)
private val mapper = ObjectMapper()

/**
 * Some local models write tool calls into the text instead of using the API field: Hermes style
 * `<tool_call>{json}</tool_call>`, or Mistral style `[TOOL_CALLS][{json}]`. Passes text through until a
 * marker appears, then buffers the rest so [finish] can parse it. Nothing is dropped if it doesn't parse.
 */
internal class TextToolCallScanner {
    private var buffer: StringBuilder? = null
    private var held = ""

    /** Returns the part of [content] that is safe to show. */
    fun feed(content: String): String {
        buffer?.let { it.append(content); return "" }
        val text = held + content
        held = ""
        val at = markers.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (at != null) {
            buffer = StringBuilder(text.substring(at))
            return text.substring(0, at)
        }
        val keep = markers.maxOf { partialSuffix(text, it) }
        held = text.substring(text.length - keep)
        return text.substring(0, text.length - keep)
    }

    fun finish(): Result {
        val text = buffer?.toString() ?: held
        held = ""
        buffer = null
        val calls = if (text.startsWith(MISTRAL)) parseMistral(text) else parseHermes(text)
        return if (calls.isEmpty()) Result(emptyList(), text) else Result(calls, "")
    }

    private fun parseHermes(text: String): List<ToolCall> {
        if (!text.startsWith(HERMES_OPEN)) return emptyList()
        val blocks = Regex("$HERMES_OPEN(.*?)$HERMES_CLOSE", RegexOption.DOT_MATCHES_ALL)
        if (blocks.replace(text, "").isNotBlank()) return emptyList()
        return toCalls(blocks.findAll(text).map { runCatching { mapper.readTree(it.groupValues[1]) }.getOrNull() }.toList())
    }

    private fun parseMistral(text: String): List<ToolCall> {
        val array = runCatching { mapper.readTree(text.removePrefix(MISTRAL)) }.getOrNull()
        return if (array != null && array.isArray) toCalls(array.toList()) else emptyList()
    }

    /** All-or-nothing: one bad entry means the whole block is treated as plain text. */
    private fun toCalls(nodes: List<JsonNode?>): List<ToolCall> {
        val calls = nodes.mapIndexedNotNull { i, node ->
            val name = node?.get("name")?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val args = node.get("arguments")
            ToolCall("text-call-$i", name, if (args == null || args.isNull) "{}" else if (args.isTextual) args.asText() else args.toString())
        }
        return if (calls.size == nodes.size) calls else emptyList()
    }
}
