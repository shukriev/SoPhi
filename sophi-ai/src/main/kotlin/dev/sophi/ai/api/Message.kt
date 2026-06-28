package dev.sophi.ai.api

enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

data class Message(
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<ToolCall>? = null
)
