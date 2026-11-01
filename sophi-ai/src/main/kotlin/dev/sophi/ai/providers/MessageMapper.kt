package dev.sophi.ai.providers

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.content.Media
import org.springframework.core.io.ByteArrayResource
import org.springframework.util.MimeTypeUtils
import dev.sophi.ai.api.loadImages
import dev.sophi.ai.api.withLeftOutNotes

internal fun Message.toSpring(): org.springframework.ai.chat.messages.Message = when (role) {
    MessageRole.USER -> loadImages(images).let { (kept, leftOut) ->
        UserMessage.builder()
            .text(withLeftOutNotes(content, leftOut))
            .media(kept.map { Media(MimeTypeUtils.parseMimeType(it.mimeType), ByteArrayResource(it.bytes)) })
            .build()
    }
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
