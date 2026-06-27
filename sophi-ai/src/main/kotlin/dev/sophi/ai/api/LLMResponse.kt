package dev.sophi.ai.api

data class TokenUsage(val inputTokens: Int, val outputTokens: Int)

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

sealed class LLMResponse {
    data class Text(
        val content: String,
        val usage: TokenUsage,
        val stopReason: String? = null
    ) : LLMResponse()

    data class ToolUse(
        val calls: List<ToolCall>,
        val usage: TokenUsage
    ) : LLMResponse()

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : LLMResponse()
}
