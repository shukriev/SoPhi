package dev.sophi.ai.providers

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.prompt.Prompt

class ClaudeProvider(
    private val chatModel: AnthropicChatModel,
    override val name: String = "claude"
) : LLMProvider {

    override suspend fun complete(request: CompletionRequest): LLMResponse =
        withContext(Dispatchers.IO) {
            runCatching { chatModel.call(request.toPrompt()) }
                .fold(
                    onSuccess = { it.toLLMResponse() },
                    onFailure = { LLMResponse.Error(it.message ?: "unknown error", it) }
                )
        }

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        // Spring AI's Anthropic streaming runs each chunk's usage through
        // UsageCalculator.getCumulativeUsage, so later chunks carry totals >= earlier ones.
        // Tracking the running max gets the same answer as reading the final chunk, and stays
        // correct even if a chunk ever reports only its own delta.
        var inputTokens = 0
        var outputTokens = 0
        chatModel.stream(request.toPrompt()).asFlow().collect { response ->
            val text = response.result?.output?.text
            if (!text.isNullOrEmpty()) emit(StreamEvent.Content(text))
            val chunkUsage = response.metadata?.usage
            inputTokens = maxOf(inputTokens, chunkUsage?.promptTokens ?: 0)
            outputTokens = maxOf(outputTokens, chunkUsage?.completionTokens ?: 0)
        }
        if (inputTokens > 0 || outputTokens > 0) {
            emit(StreamEvent.Usage(TokenUsage(inputTokens, outputTokens)))
        }
    }.catch { cause -> throw IllegalStateException("LLM stream error: ${cause.message}", cause) }

    private fun CompletionRequest.toPrompt(): Prompt {
        val springMessages = buildList {
            systemPrompt?.let { add(SystemMessage(it)) }
            addAll(messages.map { it.toSpring() })
        }
        val options = AnthropicChatOptions.builder()
            .model(model)
            .maxTokens(maxTokens)
            .temperature(temperature)
            .toolCallbacks(tools.map { SophiToolCallback(it) })
            .build()
        return Prompt(springMessages, options)
    }
}
