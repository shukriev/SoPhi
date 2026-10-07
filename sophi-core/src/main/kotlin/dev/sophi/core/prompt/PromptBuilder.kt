package dev.sophi.core.prompt

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry

object PromptBuilder {
    private val STOP_MARKER = Regex("""^\s*\[Stopped (?:early: (.*)|by the user)]\s*$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * A past turn that ended on a stop marker ("[Stopped early: …]", "[Stopped by the user]") is
     * replayed as a note instead. Verbatim, a small model learns the marker as an answer and writes
     * it itself. Also catches markers a model already wrote that way, which carry no stopReason.
     */
    private fun stopNote(content: String): String? {
        val m = STOP_MARKER.matchEntire(content) ?: return null
        val reason = m.groupValues[1]
        return if (reason.isEmpty()) "(No answer: the user stopped it.)" else "(No answer: this turn was cut off — $reason.)"
    }

    fun build(entries: List<SessionEntry>): List<Message> = entries
        .filter { it.metadata["replay"] != "false" }
        .map { entry ->
        when (entry.role) {
            EntryRole.SYSTEM -> Message(MessageRole.SYSTEM, entry.content)
            EntryRole.USER -> Message(MessageRole.USER, entry.content)
            EntryRole.ASSISTANT -> Message(MessageRole.ASSISTANT, stopNote(entry.content) ?: entry.content)
            EntryRole.TOOL_RESULT -> Message(
                role = MessageRole.TOOL,
                content = entry.content,
                toolCallId = entry.metadata["toolCallId"],
                toolName = entry.metadata["toolName"]
            )
        }
    }
}
