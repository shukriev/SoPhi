package dev.sophi.ai.providers

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

class OpenAICompatProviderTest : FunSpec({
    val mockChatModel = mockk<OpenAiChatModel>()
    val provider = OpenAICompatProvider(mockChatModel)

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

    test("name defaults to 'openai'") {
        provider.name shouldBe "openai"
    }

    test("name can be overridden for Ollama or Groq") {
        OpenAICompatProvider(mockChatModel, name = "ollama").name shouldBe "ollama"
        OpenAICompatProvider(mockChatModel, name = "groq").name shouldBe "groq"
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

    test("complete() returns ToolUse when model responds with tool calls") {
        val toolCall = mockk<AssistantMessage.ToolCall> {
            every { id() } returns "call_xyz"
            every { name() } returns "calculator"
            every { arguments() } returns """{"expression":"2+2"}"""
        }
        val usage = mockk<Usage> {
            every { this@mockk.promptTokens } returns 15
            every { this@mockk.completionTokens } returns 0
        }
        val responseMeta = mockk<ChatResponseMetadata> {
            every { this@mockk.usage } returns usage
        }
        val genMeta = mockk<ChatGenerationMetadata> {
            every { this@mockk.finishReason } returns "tool_calls"
        }
        val output = mockk<AssistantMessage> {
            every { this@mockk.text } returns null
            every { this@mockk.toolCalls } returns listOf(toolCall)
        }
        val generation = mockk<Generation> {
            every { this@mockk.output } returns output
            every { this@mockk.metadata } returns genMeta
        }
        val response = mockk<ChatResponse> {
            every { result } returns generation
            every { metadata } returns responseMeta
        }
        every { mockChatModel.call(any<Prompt>()) } returns response

        val req = CompletionRequest(
            messages = listOf(Message(MessageRole.USER, "what is 2+2")),
            model = "gpt-4o"
        )
        val result = provider.complete(req)

        result.shouldBeInstanceOf<LLMResponse.ToolUse>()
        val toolUse = result as LLMResponse.ToolUse
        toolUse.calls[0].id shouldBe "call_xyz"
        toolUse.calls[0].name shouldBe "calculator"
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
})
