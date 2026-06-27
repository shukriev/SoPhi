package dev.sophi.ai.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class CompletionRequestTest : FunSpec({
    test("defaults: maxTokens=4096, temperature=0.7, no systemPrompt, no tools") {
        val req = CompletionRequest(
            messages = listOf(Message(MessageRole.USER, "hi")),
            model = "claude-opus-4-8"
        )
        req.maxTokens shouldBe 4096
        req.temperature shouldBe 0.7
        req.systemPrompt shouldBe null
        req.tools.shouldBeEmpty()
    }

    test("tool definitions are attached when provided") {
        val tool = ToolDefinition("search", "web search", """{"type":"object","properties":{}}""")
        val req = CompletionRequest(
            messages = listOf(Message(MessageRole.USER, "hi")),
            model = "claude-opus-4-8",
            tools = listOf(tool)
        )
        req.tools.size shouldBe 1
        req.tools[0].name shouldBe "search"
        req.tools[0].description shouldBe "web search"
    }

    test("systemPrompt is passed through") {
        val req = CompletionRequest(
            messages = listOf(Message(MessageRole.USER, "hi")),
            model = "claude-opus-4-8",
            systemPrompt = "You are helpful."
        )
        req.systemPrompt shouldBe "You are helpful."
    }
})
