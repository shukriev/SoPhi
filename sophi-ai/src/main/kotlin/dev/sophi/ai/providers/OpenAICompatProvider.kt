package dev.sophi.ai.providers

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions

class OpenAICompatProvider(
    private val chatModel: OpenAiChatModel,
    override val name: String = "openai"
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
        val options = OpenAiChatOptions.builder()
            .model(model)
            .maxTokens(maxTokens)
            .temperature(temperature)
            .toolCallbacks(tools.map { SophiToolCallback(it) })
            .build()
        return Prompt(springMessages, options)
    }
}
