package dev.sophi.ai.providers

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

    override fun stream(request: CompletionRequest): Flow<String> =
        chatModel.stream(request.toPrompt())
            .filter { it.result?.output?.text?.isNotEmpty() == true }
            .map { it.result!!.output!!.text!! }
            .asFlow()
            .catch { cause -> throw IllegalStateException("LLM stream error: ${cause.message}", cause) }

    private fun CompletionRequest.toPrompt(): Prompt {
        val springMessages = buildList {
            systemPrompt?.let { add(SystemMessage(it)) }
            addAll(messages.map { it.toSpring() })
        }
        val options = AnthropicChatOptions.builder()
            .model(model)
            .maxTokens(maxTokens)
            .temperature(temperature)
            .build()
        // TODO M2: map request.tools to options.functions() when tool calling is wired in sophi-core
        return Prompt(springMessages, options)
    }
}
