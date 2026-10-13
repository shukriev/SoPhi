package dev.sophi.ai.api

/** [inputTokens] = all prompt tokens (including cache reads/writes); [cacheReadTokens] = the part served from cache. */
data class TokenUsage(val inputTokens: Int, val outputTokens: Int, val cacheReadTokens: Int = 0)

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
