package dev.sophi.ai.providers

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.ai.anthropic.AnthropicCacheStrategy
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions

class ClaudeProviderCacheTest : FunSpec({
    test("every request caches system and tools") {
        val provider = ClaudeProvider(mockk<AnthropicChatModel>())
        val prompt = with(provider) {
            CompletionRequest(
                messages = listOf(Message(MessageRole.USER, "hi")),
                model = "claude-x", maxTokens = 10, temperature = 0.0, systemPrompt = "sys"
            ).toPrompt()
        }
        (prompt.options as AnthropicChatOptions).cacheOptions.strategy shouldBe AnthropicCacheStrategy.SYSTEM_AND_TOOLS
    }
})
