package dev.sophi.core.prompt

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry

object PromptBuilder {
    fun build(entries: List<SessionEntry>): List<Message> = entries.map { entry ->
        when (entry.role) {
            EntryRole.SYSTEM -> Message(MessageRole.SYSTEM, entry.content)
            EntryRole.USER -> Message(MessageRole.USER, entry.content)
            EntryRole.ASSISTANT -> Message(MessageRole.ASSISTANT, entry.content)
            EntryRole.TOOL_RESULT -> Message(
                role = MessageRole.TOOL,
                content = entry.content,
                toolCallId = entry.metadata["toolCallId"],
                toolName = entry.metadata["toolName"]
            )
        }
    }
}
