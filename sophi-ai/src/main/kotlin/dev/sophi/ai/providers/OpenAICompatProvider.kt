package dev.sophi.ai.providers

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.chat.completions.ChatCompletionStreamOptions
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions

private val reasoningFieldNames = listOf("reasoning", "reasoning_content")
private val jsonMapper = ObjectMapper()

class OpenAICompatProvider(
    private val chatModel: OpenAiChatModel,
    private val rawClient: OpenAIClient,
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

    // iterator.hasNext()/next() below are blocking calls with no cancellation checkpoints, so a
    // stalled network read would otherwise ignore coroutine cancellation forever. Running them on
    // a dedicated thread and closing the response from awaitClose (which fires the instant
    // cancellation is requested, not just once the thread notices) is what actually unblocks it.
    override fun stream(request: CompletionRequest): Flow<StreamEvent> = callbackFlow {
        val params = request.toCreateParams()
        val merger = ToolCallDeltaMerger()
        val streamResponse = rawClient.chat().completions().createStreaming(params)
        val worker = Thread({
            try {
                val iterator = streamResponse.stream().iterator()
                while (iterator.hasNext()) {
                    val chunk = iterator.next()
                    chunk.choices().forEach { choice ->
                        val delta = choice.delta()
                        val content = delta.content().orElse(null)
                        if (!content.isNullOrEmpty()) trySend(StreamEvent.Content(content))

                        val reasoningText = reasoningFieldNames.firstNotNullOfOrNull { key ->
                            delta._additionalProperties()[key]?.convert(String::class.java)
                        }
                        if (!reasoningText.isNullOrEmpty()) trySend(StreamEvent.Reasoning(reasoningText))

                        delta.toolCalls().orElse(null)?.forEach { merger.accumulate(it) }

                        if (choice.finishReason().isPresent) {
                            val merged = merger.build()
                            if (merged.isNotEmpty()) trySend(StreamEvent.ToolCallsReady(merged))
                        }
                    }
                    // Present only on the final chunk (per the OpenAI streaming convention), and
                    // that chunk carries no choices — so this must live outside the loop above.
                    chunk.usage().orElse(null)?.let { u ->
                        trySend(StreamEvent.Usage(
                            TokenUsage(u.promptTokens().toInt(), u.completionTokens().toInt())
                        ))
                    }
                }
                close()
            } catch (e: Exception) {
                close(e)
            }
        }, "openai-compat-stream")
        worker.isDaemon = true
        worker.start()
        awaitClose { runCatching { streamResponse.close() } }
    }.catch { cause -> throw IllegalStateException("LLM stream error: ${cause.message}", cause) }

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
            .reasoningEffort(reasoningEffort)
            .build()
        return Prompt(springMessages, options)
    }

    private fun CompletionRequest.toCreateParams(): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
            .model(model)
            .maxCompletionTokens(maxTokens.toLong())
            .temperature(temperature)
            // Without this the server sends no usage on any streamed chunk at all.
            .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
        systemPrompt?.let { builder.addMessage(ChatCompletionSystemMessageParam.builder().content(it).build()) }
        messages.forEach { message -> builder.addMessage(message.toRawMessageParam()) }
        tools.forEach { tool ->
            builder.addTool(
                ChatCompletionFunctionTool.builder()
                    .function(
                        FunctionDefinition.builder()
                            .name(tool.name)
                            .description(tool.description)
                            .parameters(tool.parametersJson.toFunctionParameters())
                            .build()
                    )
                    .build()
            )
        }
        return builder.build()
    }

    private fun Message.toRawMessageParam(): ChatCompletionMessageParam = when (role) {
        MessageRole.SYSTEM -> ChatCompletionMessageParam.ofSystem(
            ChatCompletionSystemMessageParam.builder().content(content).build()
        )
        MessageRole.USER -> ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(content).build()
        )
        MessageRole.ASSISTANT -> {
            val assistantBuilder = ChatCompletionAssistantMessageParam.builder().content(content)
            toolCalls?.forEach { tc ->
                assistantBuilder.addToolCall(
                    ChatCompletionMessageFunctionToolCall.builder()
                        .id(tc.id)
                        .function(
                            ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name(tc.name)
                                .arguments(tc.argumentsJson)
                                .build()
                        )
                        .build()
                )
            }
            ChatCompletionMessageParam.ofAssistant(assistantBuilder.build())
        }
        MessageRole.TOOL -> ChatCompletionMessageParam.ofTool(
            ChatCompletionToolMessageParam.builder()
                .content(content)
                .toolCallId(toolCallId ?: "")
                .build()
        )
    }

    private fun String.toFunctionParameters(): FunctionParameters {
        val node = jsonMapper.readTree(this)
        val propsBuilder = FunctionParameters.builder()
        node.fields().forEach { (key, value) -> propsBuilder.putAdditionalProperty(key, JsonValue.fromJsonNode(value)) }
        return propsBuilder.build()
    }
}
