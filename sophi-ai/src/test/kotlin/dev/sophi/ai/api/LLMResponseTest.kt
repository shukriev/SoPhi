package dev.sophi.ai.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LLMResponseTest : FunSpec({
    test("Text carries content, usage, and optional stopReason") {
        val resp = LLMResponse.Text("hello", TokenUsage(10, 5))
        resp.shouldBeInstanceOf<LLMResponse>()
        resp.content shouldBe "hello"
        resp.usage.inputTokens shouldBe 10
        resp.usage.outputTokens shouldBe 5
        resp.stopReason shouldBe null
    }

    test("Text stopReason is set when provided") {
        val resp = LLMResponse.Text("bye", TokenUsage(1, 1), stopReason = "end_turn")
        resp.stopReason shouldBe "end_turn"
    }

    test("ToolUse carries a list of ToolCalls") {
        val call = ToolCall("id1", "search", """{"q":"kotlin coroutines"}""")
        val resp = LLMResponse.ToolUse(listOf(call), TokenUsage(20, 0))
        resp.calls.size shouldBe 1
        resp.calls[0].id shouldBe "id1"
        resp.calls[0].name shouldBe "search"
        resp.calls[0].argumentsJson shouldBe """{"q":"kotlin coroutines"}"""
    }

    test("Error carries message and optional cause") {
        val ex = RuntimeException("timeout")
        val resp = LLMResponse.Error("request timed out", ex)
        resp.message shouldBe "request timed out"
        resp.cause shouldBe ex
    }

    test("Error cause is null by default") {
        val resp = LLMResponse.Error("rate limit exceeded")
        resp.cause shouldBe null
    }
})
