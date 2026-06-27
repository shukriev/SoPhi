package dev.sophi.ai.providers

import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.ToolCall
import dev.sophi.ai.api.TokenUsage
import org.springframework.ai.chat.model.ChatResponse

internal fun ChatResponse.toLLMResponse(): LLMResponse {
    val output = result?.output
        ?: return LLMResponse.Error("null output from model")
    val usage = TokenUsage(
        inputTokens = metadata.usage.promptTokens ?: 0,
        outputTokens = metadata.usage.completionTokens ?: 0
    )
    return if (output.toolCalls.isNotEmpty()) {
        LLMResponse.ToolUse(
            calls = output.toolCalls.map { ToolCall(it.id(), it.name(), it.arguments()) },
            usage = usage
        )
    } else {
        LLMResponse.Text(
            content = output.text ?: "",
            usage = usage,
            stopReason = result!!.metadata.finishReason
        )
    }
}
