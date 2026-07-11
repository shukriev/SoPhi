package dev.sophi.learning.export

import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import kotlinx.serialization.json.*

class SftBuilder(private val redact: (String) -> String) {
    fun build(entries: List<SessionEntry>, systemPrompt: String?): String =
        line(ChatMessages.fromEntries(entries, systemPrompt))

    /**
     * Generates one training example per assistant turn (including tool-call emissions).
     * Each example shows a conversation prefix up to and including the assistant's response,
     * treating tool calls as explicit targets that teach when and how to call tools.
     */
    fun buildPerTurn(entries: List<SessionEntry>, systemPrompt: String?): List<String> {
        val assistantReplyIdx = entries.withIndex()
            .filter { (_, e) -> e.role == EntryRole.ASSISTANT }
            .map { it.index }
        return assistantReplyIdx.map { idx ->
            line(ChatMessages.fromEntries(entries.take(idx + 1), systemPrompt))
        }
    }

    private fun line(messages: JsonArray): String {
        val redacted = messages.redactedWith(redact)
        return buildJsonObject { put("messages", redacted) }.toString()
    }
}
