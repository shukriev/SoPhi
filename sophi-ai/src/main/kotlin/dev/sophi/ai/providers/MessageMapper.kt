package dev.sophi.ai.providers

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

internal fun Message.toSpring(): org.springframework.ai.chat.messages.Message = when (role) {
    MessageRole.USER -> UserMessage(content)
    MessageRole.ASSISTANT -> {
        val tcs = toolCalls
        if (!tcs.isNullOrEmpty()) {
            AssistantMessage.builder()
                .content(content)
                .toolCalls(tcs.map { tc -> AssistantMessage.ToolCall(tc.id, "function", tc.name, tc.argumentsJson) })
                .build()
        } else {
            AssistantMessage(content)
        }
    }
    MessageRole.SYSTEM -> SystemMessage(content)
    MessageRole.TOOL -> ToolResponseMessage.builder()
        .responses(listOf(ToolResponseMessage.ToolResponse(toolCallId ?: "", toolName ?: "", content)))
        .build()
}
