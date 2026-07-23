package dev.sophi.ai.providers

import com.openai.client.OpenAIClient
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.ChatCompletionChunk
import dev.sophi.ai.api.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Spliterators
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.stream.StreamSupport

private fun chunk(
    content: String? = null,
    reasoning: String? = null,
    toolCallIndex: Long? = null,
    toolCallId: String? = null,
    toolCallName: String? = null,
    toolCallArgs: String? = null,
    finished: Boolean = false
): ChatCompletionChunk {
    val deltaBuilder = ChatCompletionChunk.Choice.Delta.builder()
    content?.let { deltaBuilder.content(it) }
    reasoning?.let { deltaBuilder.putAdditionalProperty("reasoning", com.openai.core.JsonValue.from(it)) }
    if (toolCallIndex != null) {
        val tcBuilder = ChatCompletionChunk.Choice.Delta.ToolCall.builder().index(toolCallIndex)
        toolCallId?.let { tcBuilder.id(it) }
        if (toolCallName != null || toolCallArgs != null) {
            val fnBuilder = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
            toolCallName?.let { fnBuilder.name(it) }
            toolCallArgs?.let { fnBuilder.arguments(it) }
            tcBuilder.function(fnBuilder.build())
        }
        deltaBuilder.addToolCall(tcBuilder.build())
    }
    val choiceBuilder = ChatCompletionChunk.Choice.builder().index(0).delta(deltaBuilder.build())
    choiceBuilder.finishReason(
        if (finished) java.util.Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP)
        else java.util.Optional.empty()
    )
    return ChatCompletionChunk.builder()
        .id("chunk-1").model("m").created(0L)
        .addChoice(choiceBuilder.build())
        .build()
}

private fun fakeStreamResponse(chunks: List<ChatCompletionChunk>): StreamResponse<ChatCompletionChunk> {
    val response = mockk<StreamResponse<ChatCompletionChunk>>()
    every { response.stream() } returns chunks.stream()
    every { response.close() } returns Unit
    return response
}

class OpenAICompatProviderTest : FunSpec({
    val mockChatModel = mockk<OpenAiChatModel>()
    val mockRawClient = mockk<OpenAIClient>()
    val provider = OpenAICompatProvider(mockChatModel, mockRawClient)

    fun stubTextResponse(
        text: String,
        promptTokens: Int = 8,
        genTokens: Int = 4,
        finishReason: String = "stop"
    ): ChatResponse {
        val usage = mockk<Usage> {
            every { this@mockk.promptTokens } returns promptTokens
            every { this@mockk.completionTokens } returns genTokens
        }
        val responseMeta = mockk<ChatResponseMetadata> {
            every { this@mockk.usage } returns usage
        }
        val genMeta = mockk<ChatGenerationMetadata> {
            every { this@mockk.finishReason } returns finishReason
        }
        val output = mockk<AssistantMessage> {
            every { this@mockk.text } returns text
            every { this@mockk.toolCalls } returns emptyList<AssistantMessage.ToolCall>()
        }
        val generation = mockk<Generation> {
            every { this@mockk.output } returns output
            every { this@mockk.metadata } returns genMeta
        }
        return mockk<ChatResponse> {
            every { result } returns generation
            every { metadata } returns responseMeta
        }
    }

    fun stubStreaming(chunks: List<ChatCompletionChunk>) {
        every {
            mockRawClient.chat().completions().createStreaming(any<com.openai.models.chat.completions.ChatCompletionCreateParams>())
        } returns fakeStreamResponse(chunks)
    }

    test("name defaults to 'openai'") {
        provider.name shouldBe "openai"
    }

    test("name can be overridden for Ollama or Groq") {
        OpenAICompatProvider(mockChatModel, mockRawClient, name = "ollama").name shouldBe "ollama"
        OpenAICompatProvider(mockChatModel, mockRawClient, name = "groq").name shouldBe "groq"
    }

    test("complete() returns Text for a plain text response") {
        every { mockChatModel.call(any<Prompt>()) } returns stubTextResponse("hello from openai")
        val req = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), "gpt-4o")

        val result = provider.complete(req)

        result.shouldBeInstanceOf<LLMResponse.Text>()
        (result as LLMResponse.Text).content shouldBe "hello from openai"
        result.usage.inputTokens shouldBe 8
        result.usage.outputTokens shouldBe 4
        result.stopReason shouldBe "stop"
    }

    test("complete() wraps model exceptions in LLMResponse.Error") {
        every { mockChatModel.call(any<Prompt>()) } throws RuntimeException("context length exceeded")
        val req = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), "gpt-4o")

        val result = provider.complete(req)

        result.shouldBeInstanceOf<LLMResponse.Error>()
        (result as LLMResponse.Error).message shouldBe "context length exceeded"
    }

    test("complete() sends systemPrompt as first SystemMessage in Prompt") {
        val capturedPrompt = slot<Prompt>()
        every { mockChatModel.call(capture(capturedPrompt)) } returns stubTextResponse("ok")

        val req = CompletionRequest(
            messages = listOf(Message(MessageRole.USER, "hello")),
            model = "gpt-4o",
            systemPrompt = "You are a concise assistant."
        )
        provider.complete(req)

        val instructions = capturedPrompt.captured.instructions
        instructions[0].shouldBeInstanceOf<SystemMessage>()
        (instructions[0] as SystemMessage).text shouldBe "You are a concise assistant."
        instructions[1].shouldBeInstanceOf<UserMessage>()
    }

    test("complete() maps request.tools into OpenAiChatOptions.toolCallbacks") {
        val capturedPrompt = slot<Prompt>()
        every { mockChatModel.call(capture(capturedPrompt)) } returns stubTextResponse("ok")

        val req = CompletionRequest(
            messages = listOf(Message(MessageRole.USER, "search for kotlin")),
            model = "qwen2.5:7b",
            tools = listOf(ToolDefinition("search", "Searches the web", """{"type":"object"}"""))
        )
        provider.complete(req)

        val options = capturedPrompt.captured.options as org.springframework.ai.model.tool.ToolCallingChatOptions
        options.toolCallbacks!!.size shouldBe 1
        options.toolCallbacks!![0].toolDefinition.name() shouldBe "search"
    }

    test("stream() emits StreamEvent.Content for content chunks") {
        stubStreaming(listOf(chunk(content = "hello"), chunk(content = " world"), chunk(finished = true)))
        val req = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), "qwen3.5:9b")

        val events = runBlocking { provider.stream(req).toList() }

        events shouldBe listOf(StreamEvent.Content("hello"), StreamEvent.Content(" world"))
    }

    test("stream() emits StreamEvent.Reasoning from the 'reasoning' additionalProperty (Ollama convention)") {
        stubStreaming(listOf(chunk(reasoning = "thinking..."), chunk(content = "answer"), chunk(finished = true)))
        val req = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), "qwen3.5:9b")

        val events = runBlocking { provider.stream(req).toList() }

        events shouldBe listOf(StreamEvent.Reasoning("thinking..."), StreamEvent.Content("answer"))
    }

    test("stream() emits one merged StreamEvent.ToolCallsReady on the finishing chunk") {
        stubStreaming(listOf(
            chunk(toolCallIndex = 0, toolCallId = "call_1", toolCallName = "get_weather", toolCallArgs = "{\"city\":"),
            chunk(toolCallIndex = 0, toolCallArgs = "\"Paris\"}"),
            chunk(finished = true)
        ))
        val req = CompletionRequest(listOf(Message(MessageRole.USER, "weather in paris")), "qwen3.5:9b")

        val events = runBlocking { provider.stream(req).toList() }

        events shouldBe listOf(StreamEvent.ToolCallsReady(listOf(ToolCall("call_1", "get_weather", "{\"city\":\"Paris\"}"))))
    }

    test("stream() does not emit ToolCallsReady when no tool calls were accumulated") {
        stubStreaming(listOf(chunk(content = "just an answer"), chunk(finished = true)))
        val req = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), "qwen3.5:9b")

        val events = runBlocking { provider.stream(req).toList() }

        events shouldBe listOf(StreamEvent.Content("just an answer"))
    }

    test("stream() closes the underlying StreamResponse on cancellation, unblocking a stalled read") {
        // Simulates a stalled network read: hasNext() blocks on stalledGate forever unless
        // something closes the response, exactly like a real socket read that only unblocks
        // when the connection is closed out from under it.
        val blockedOnSecondRead = CountDownLatch(1)
        val stalledGate = CountDownLatch(1)
        val blockingIterator = object : Iterator<ChatCompletionChunk> {
            var index = 0
            override fun hasNext(): Boolean {
                if (index == 0) return true
                blockedOnSecondRead.countDown()
                stalledGate.await()
                return false
            }
            override fun next(): ChatCompletionChunk {
                index++
                return chunk(content = "hello")
            }
        }
        val blockingStream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(blockingIterator, 0), false)
        val response = mockk<StreamResponse<ChatCompletionChunk>>()
        every { response.stream() } returns blockingStream
        every { response.close() } answers { stalledGate.countDown() }
        every {
            mockRawClient.chat().completions().createStreaming(any<com.openai.models.chat.completions.ChatCompletionCreateParams>())
        } returns response
        val req = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), "qwen3.5:9b")

        runBlocking {
            val job = launch(Dispatchers.Default) { provider.stream(req).toList() }
            withTimeout(2000) {
                while (!blockedOnSecondRead.await(10, TimeUnit.MILLISECONDS)) delay(10)
            }
            job.cancel()
            withTimeout(2000) { job.join() }
        }
    }
})
