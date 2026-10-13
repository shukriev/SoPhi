package dev.sophi.ai.providers

import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.ToolCall
import dev.sophi.ai.api.TokenUsage
import org.springframework.ai.chat.model.ChatResponse

/** [cacheOutsidePrompt]: true for Anthropic, whose prompt tokens exclude cache reads/writes. */
internal fun ChatResponse.toLLMResponse(cacheOutsidePrompt: Boolean = false): LLMResponse {
    val output = result?.output
        ?: return LLMResponse.Error("null output from model")
    val usage = TokenUsage(
        inputTokens = (metadata.usage.promptTokens ?: 0) +
            if (cacheOutsidePrompt) (metadata.usage.cacheReadInputTokens ?: 0L).toInt() + (metadata.usage.cacheWriteInputTokens ?: 0L).toInt() else 0,
        outputTokens = metadata.usage.completionTokens ?: 0,
        cacheReadTokens = metadata.usage.cacheReadInputTokens?.toInt() ?: 0
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
