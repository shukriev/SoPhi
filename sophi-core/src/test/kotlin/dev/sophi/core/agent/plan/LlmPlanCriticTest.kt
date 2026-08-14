package dev.sophi.core.agent.plan

import dev.sophi.ai.api.CompletionRequest
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

class LlmPlanCriticTest : FunSpec({
    val candidate = Plan(
        id = "plan_1",
        goalPrompt = "ship the release",
        steps = listOf(PlanStep(id = "s1", instruction = "retry the build with a clean cache"))
    )

    test("score parses a number from the model response") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("0.75", TokenUsage(10, 1))
        val critic = LlmPlanCritic(provider, model = "test-model")

        val result = runBlocking { critic.score("ship the release", candidate, "step s1 failed") }
        result shouldBe 0.75
    }

    test("score parses a boundary value of 0.0") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("0.0", TokenUsage(10, 1))
        val critic = LlmPlanCritic(provider, model = "test-model")

        val result = runBlocking { critic.score("goal", candidate, "reason") }
        result shouldBe 0.0
    }

    test("score fails OPEN (1.0) when the response is unparseable") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("looks alright to me", TokenUsage(10, 1))
        val critic = LlmPlanCritic(provider, model = "test-model")

        val result = runBlocking { critic.score("goal", candidate, "reason") }
        result shouldBe 1.0
    }

    test("score fails OPEN when the provider throws") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } throws IllegalStateException("provider down")
        val critic = LlmPlanCritic(provider, model = "test-model")

        val result = runBlocking { critic.score("goal", candidate, "reason") }
        result shouldBe 1.0
    }

    test("score fails OPEN when the provider returns a non-text response") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Error("boom")
        val critic = LlmPlanCritic(provider, model = "test-model")

        val result = runBlocking { critic.score("goal", candidate, "reason") }
        result shouldBe 1.0
    }

    test("score fails OPEN when the provider exceeds the timeout") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } coAnswers {
            delay(200)
            LLMResponse.Text("0.1", TokenUsage(10, 1))
        }
        val critic = LlmPlanCritic(provider, model = "test-model", timeout = 10.milliseconds)

        val result = runBlocking { critic.score("goal", candidate, "reason") }
        result shouldBe 1.0
    }

    test("the prompt carries the failure reason and every candidate step instruction") {
        val provider = mockk<LLMProvider>()
        val requests = mutableListOf<CompletionRequest>()
        coEvery { provider.complete(capture(requests)) } returns LLMResponse.Text("0.5", TokenUsage(10, 1))
        val critic = LlmPlanCritic(provider, model = "test-model")

        runBlocking { critic.score("ship the release", candidate, "step s1 failed") }

        val userText = requests.single().messages.last().content
        userText.contains("step s1 failed") shouldBe true
        userText.contains("retry the build with a clean cache") shouldBe true
    }
})
