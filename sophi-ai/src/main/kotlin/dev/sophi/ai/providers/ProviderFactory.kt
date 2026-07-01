package dev.sophi.ai.providers

import dev.sophi.ai.api.LLMProvider
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.setup.OpenAiSetup
import java.time.Duration

fun buildClaudeProvider(apiKey: String, model: String = "claude-3-5-sonnet-20241022"): LLMProvider {
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

/**
 * Builds an OpenAI-compatible provider pointed at [baseUrl] — works for Ollama
 * (e.g. http://localhost:11434/v1), vLLM (e.g. http://localhost:8000/v1), or any other
 * server implementing the OpenAI chat-completions API. Passing [apiKey] = null puts
 * the underlying client in no-auth mode (OpenAiSetup strips the Authorization header),
 * which is what most local servers expect.
 */
fun buildOpenAiCompatProvider(
    baseUrl: String,
    apiKey: String?,
    model: String,
    name: String = "openai-compat"
): LLMProvider {
    val client = OpenAiSetup.setupSyncClient(
        baseUrl,
        apiKey ?: "",
        null,
        null,
        null,
        null,
        false,
        false,
        model,
        Duration.ofSeconds(60),
        2,
        null,
        null,
        ObservationRegistry.NOOP,
        null,
        null
    )
    val options = OpenAiChatOptions.builder()
        .baseUrl(baseUrl)
        .apiKey(apiKey ?: "")
        .model(model)
        .build()
    val chatModel = OpenAiChatModel.builder()
        .openAiClient(client)
        .options(options)
        .build()
    return OpenAICompatProvider(chatModel, name = name)
}
