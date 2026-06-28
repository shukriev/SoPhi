package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.providers.ClaudeProvider
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions

internal fun buildClaudeProvider(apiKey: String, model: String = "claude-3-5-sonnet-20241022"): LLMProvider {
    val options = AnthropicChatOptions.builder()
        .apiKey(apiKey)
        .model(model)
        .maxTokens(4096)
        .build()
    val chatModel = AnthropicChatModel.builder()
        .options(options)
        .build()
    return ClaudeProvider(chatModel)
}
