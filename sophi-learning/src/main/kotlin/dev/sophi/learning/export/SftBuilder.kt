package dev.sophi.learning.export

import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import kotlinx.serialization.json.*

class SftBuilder(private val redact: (String) -> String) {
    fun build(entries: List<SessionEntry>, systemPrompt: String?): String =
        line(ChatMessages.fromEntries(entries, systemPrompt))

    fun buildPerTurn(entries: List<SessionEntry>, systemPrompt: String?): List<String> {
        val assistantReplyIdx = entries.withIndex()
            .filter { (_, e) -> e.role == EntryRole.ASSISTANT }
            .map { it.index }
        return assistantReplyIdx.map { idx ->
            line(ChatMessages.fromEntries(entries.take(idx + 1), systemPrompt))
        }
    }

    private fun line(messages: JsonArray): String {
        val redacted = redactJson(messages)
        return buildJsonObject { put("messages", redacted) }.toString()
    }

    private fun redactJson(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content)) else element
        is JsonArray -> JsonArray(element.map { redactJson(it) })
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> redactJson(v) })
    }
}
