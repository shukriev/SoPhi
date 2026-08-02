package dev.sophi.core.agent.plan

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

class LlmStepCriticTest : FunSpec({
    val step = PlanStep(id = "s1", instruction = "write the changelog entry")

    test("judge parses a confidence number from the model response") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("0.85", TokenUsage(10, 1))
        val critic = LlmStepCritic(provider, model = "test-model")

        val result = runBlocking { critic.judge(step, "Added the changelog entry.") }
        result shouldBe 0.85
    }

    test("judge parses a boundary value of 1.0") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("1.0", TokenUsage(10, 1))
        val critic = LlmStepCritic(provider, model = "test-model")

        val result = runBlocking { critic.judge(step, "done") }
        result shouldBe 1.0
    }

    test("judge fails OPEN (confidence 1.0) when the response is unparseable") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("uh, looks fine I guess", TokenUsage(10, 1))
        val critic = LlmStepCritic(provider, model = "test-model")

        val result = runBlocking { critic.judge(step, "done") }
        result shouldBe 1.0
    }

    test("judge fails OPEN when the provider throws") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } throws IllegalStateException("provider down")
        val critic = LlmStepCritic(provider, model = "test-model")

        val result = runBlocking { critic.judge(step, "done") }
        result shouldBe 1.0
    }

    test("judge fails OPEN when the provider returns a non-text response") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Error("boom")
        val critic = LlmStepCritic(provider, model = "test-model")

        val result = runBlocking { critic.judge(step, "done") }
        result shouldBe 1.0
    }

    test("judge fails OPEN when the provider exceeds the timeout") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } coAnswers {
            delay(200)
            LLMResponse.Text("0.1", TokenUsage(10, 1))
        }
        val critic = LlmStepCritic(provider, model = "test-model", timeout = 10.milliseconds)

        val result = runBlocking { critic.judge(step, "done") }
        result shouldBe 1.0
    }
})
