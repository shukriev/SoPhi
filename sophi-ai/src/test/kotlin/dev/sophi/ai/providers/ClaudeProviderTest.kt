package dev.sophi.ai.providers

import dev.sophi.ai.api.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.prompt.Prompt

class ClaudeProviderTest : FunSpec({
    val mockChatModel = mockk<AnthropicChatModel>()
    val provider = ClaudeProvider(mockChatModel)

    fun stubTextResponse(
        text: String,
        promptTokens: Int = 10,
        genTokens: Int = 5,
        finishReason: String = "end_turn"
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

    test("name defaults to 'claude'") {
        provider.name shouldBe "claude"
    }

    test("name can be overridden") {
        ClaudeProvider(mockChatModel, name = "claude-3").name shouldBe "claude-3"
    }

    test("complete() returns Text for a plain text response") {
        every { mockChatModel.call(any<Prompt>()) } returns stubTextResponse("hello from claude")
        val req = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), "claude-opus-4-8")

        val result = provider.complete(req)

        result.shouldBeInstanceOf<LLMResponse.Text>()
        (result as LLMResponse.Text).content shouldBe "hello from claude"
        result.usage.inputTokens shouldBe 10
        result.usage.outputTokens shouldBe 5
        result.stopReason shouldBe "end_turn"
    }

    test("complete() wraps model exceptions in LLMResponse.Error") {
        every { mockChatModel.call(any<Prompt>()) } throws RuntimeException("rate limit exceeded")
        val req = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), "claude-opus-4-8")

        val result = provider.complete(req)

        result.shouldBeInstanceOf<LLMResponse.Error>()
        (result as LLMResponse.Error).message shouldBe "rate limit exceeded"
        result.cause?.message shouldBe "rate limit exceeded"
    }

    test("complete() returns ToolUse when model responds with tool calls") {
        val toolCall = mockk<AssistantMessage.ToolCall> {
            every { id() } returns "call_abc"
            every { name() } returns "search"
            every { arguments() } returns """{"q":"kotlin"}"""
        }
        val usage = mockk<Usage> {
            every { promptTokens } returns 20
            every { completionTokens } returns 0
        }
        val responseMeta = mockk<ChatResponseMetadata> {
            every { this@mockk.usage } returns usage
        }
        val genMeta = mockk<ChatGenerationMetadata> {
            every { finishReason } returns "tool_use"
        }
        val output = mockk<AssistantMessage> {
            every { text } returns null
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
            messages = listOf(Message(MessageRole.USER, "search for kotlin")),
            model = "claude-opus-4-8"
        )
        val result = provider.complete(req)

        result.shouldBeInstanceOf<LLMResponse.ToolUse>()
        val toolUse = result as LLMResponse.ToolUse
        toolUse.calls.size shouldBe 1
        toolUse.calls[0].id shouldBe "call_abc"
        toolUse.calls[0].name shouldBe "search"
        toolUse.calls[0].argumentsJson shouldBe """{"q":"kotlin"}"""
        toolUse.usage.inputTokens shouldBe 20
    }

    test("complete() sends systemPrompt as first SystemMessage in Prompt") {
        val capturedPrompt = slot<Prompt>()
        every { mockChatModel.call(capture(capturedPrompt)) } returns stubTextResponse("ok")

        val req = CompletionRequest(
            messages = listOf(Message(MessageRole.USER, "hello")),
            model = "claude-opus-4-8",
            systemPrompt = "You are a helpful assistant."
        )
        provider.complete(req)

        val instructions = capturedPrompt.captured.instructions
        instructions[0].shouldBeInstanceOf<SystemMessage>()
        (instructions[0] as SystemMessage).text shouldBe "You are a helpful assistant."
        instructions[1].shouldBeInstanceOf<UserMessage>()
        (instructions[1] as UserMessage).text shouldBe "hello"
    }

    test("complete() omits SystemMessage when systemPrompt is null") {
        val capturedPrompt = slot<Prompt>()
        every { mockChatModel.call(capture(capturedPrompt)) } returns stubTextResponse("ok")

        val req = CompletionRequest(
            messages = listOf(Message(MessageRole.USER, "hello")),
            model = "claude-opus-4-8"
        )
        provider.complete(req)

        val instructions = capturedPrompt.captured.instructions
        instructions.size shouldBe 1
        instructions[0].shouldBeInstanceOf<UserMessage>()
    }
})
