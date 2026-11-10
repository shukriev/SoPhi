package dev.sophi.ai.providers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.sophi.ai.api.ToolCall

internal data class Result(val calls: List<ToolCall>, val leftoverContent: String)

private const val HERMES_OPEN = "<tool_call>"
private const val HERMES_CLOSE = "</tool_call>"
private const val MISTRAL = "[TOOL_CALLS]"
private val markers = listOf(HERMES_OPEN, MISTRAL)
private val hermesBlocks = Regex("$HERMES_OPEN(.*?)$HERMES_CLOSE", RegexOption.DOT_MATCHES_ALL)
private val mapper = ObjectMapper()
// Trailing tokens must fail the parse, or text after the JSON would be silently dropped.
private val reader = mapper.readerFor(JsonNode::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

/**
 * Some local models write tool calls into the text instead of using the API field: Hermes style
 * `<tool_call>{json}</tool_call>`, or Mistral style `[TOOL_CALLS][{json}]`. Passes text through until a
 * marker appears, then buffers the rest so [finish] can parse it. Nothing is dropped if it doesn't parse.
 * Not recognised: the newer Mistral `[TOOL_CALLS]name[ARGS]{json}` form, which is shown as plain text.
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
        if (hermesBlocks.replace(text, "").isNotBlank()) return emptyList()
        return toCalls(hermesBlocks.findAll(text).map { runCatching { reader.readTree(it.groupValues[1]) }.getOrNull() }.toList())
    }

    private fun parseMistral(text: String): List<ToolCall> {
        val array = runCatching { reader.readTree(text.removePrefix(MISTRAL)) }.getOrNull()
        return if (array != null && array.isArray) toCalls(array.toList()) else emptyList()
    }
}

/**
 * True when, from the first marker on, the text starts with valid call blocks in a format the scanner
 * knows and non-blank text follows them. The stream shows such a reply as plain text and runs nothing,
 * so the caller can tell the model its call was not run. A marker with JSON that doesn't parse is not a call.
 */
fun hasUnrunTextToolCall(text: String): Boolean {
    val at = markers.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return false
    val rest = text.substring(at)
    val after = if (rest.startsWith(MISTRAL)) afterMistralCalls(rest.removePrefix(MISTRAL)) else afterHermesCalls(rest)
    return !after.isNullOrBlank()
}

/** The text after the leading run of Hermes blocks, or null if they aren't all valid calls. */
private fun afterHermesCalls(text: String): String? {
    var end = 0
    val nodes = mutableListOf<JsonNode?>()
    for (m in hermesBlocks.findAll(text)) {
        if (text.substring(end, m.range.first).isNotBlank()) break
        nodes += runCatching { reader.readTree(m.groupValues[1]) }.getOrNull()
        end = m.range.last + 1
    }
    return if (toCalls(nodes).isEmpty()) null else text.substring(end)
}

/** The text after the first JSON value, or null if that value isn't an array of valid calls. */
private fun afterMistralCalls(body: String): String? = runCatching {
    mapper.createParser(body).use { p ->
        val array = mapper.readTree<JsonNode>(p)
        if (array != null && array.isArray && toCalls(array.toList()).isNotEmpty()) body.substring(p.currentLocation().charOffset.toInt()) else null
    }
}.getOrNull()

/** All-or-nothing: one bad entry means the whole block is treated as plain text. */
private fun toCalls(nodes: List<JsonNode?>): List<ToolCall> {
    val calls = nodes.mapNotNull { node ->
        val name = node?.get("name")?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val args = node.get("arguments")
        ToolCall(newCallId(), name, if (args == null || args.isNull) "{}" else if (args.isTextual) args.asText() else args.toString())
    }
    return if (calls.size == nodes.size) calls else emptyList()
}

private val idChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')

/** Random, so ids don't repeat across rounds of a turn; 9 alphanumerics, as Mistral chat templates require. */
private fun newCallId(): String = String(CharArray(9) { idChars.random() })
