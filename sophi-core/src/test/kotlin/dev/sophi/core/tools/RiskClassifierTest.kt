package dev.sophi.core.tools

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

class LlmRiskClassifierTest : FunSpec({
    test("classify returns LOW_RISK when the model answers LOW_RISK") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("LOW_RISK", TokenUsage(10, 1))
        val classifier = LlmRiskClassifier(provider, model = "test-model")

        val result = runBlocking {
            classifier.classify("bash", "Run a shell command", RiskLevel.DESTRUCTIVE, """{"command":"ls"}""")
        }
        result shouldBe RuleVerdict.LOW_RISK
    }

    test("classify returns HIGH_RISK when the model answers HIGH_RISK") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("HIGH_RISK", TokenUsage(10, 1))
        val classifier = LlmRiskClassifier(provider, model = "test-model")

        val result = runBlocking {
            classifier.classify("bash", "Run a shell command", RiskLevel.DESTRUCTIVE, """{"command":"rm x"}""")
        }
        result shouldBe RuleVerdict.HIGH_RISK
    }

    test("classify fails safe to HIGH_RISK when the model response is unparseable") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("uh, maybe fine?", TokenUsage(10, 1))
        val classifier = LlmRiskClassifier(provider, model = "test-model")

        val result = runBlocking {
            classifier.classify("bash", "Run a shell command", RiskLevel.DESTRUCTIVE, """{"command":"rm x"}""")
        }
        result shouldBe RuleVerdict.HIGH_RISK
    }

    test("classify fails safe to HIGH_RISK when the provider throws") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } throws IllegalStateException("provider down")
        val classifier = LlmRiskClassifier(provider, model = "test-model")

        val result = runBlocking {
            classifier.classify("bash", "Run a shell command", RiskLevel.DESTRUCTIVE, """{"command":"rm x"}""")
        }
        result shouldBe RuleVerdict.HIGH_RISK
    }

    test("classify fails safe to HIGH_RISK when the provider returns a non-text response") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Error("boom")
        val classifier = LlmRiskClassifier(provider, model = "test-model")

        val result = runBlocking {
            classifier.classify("bash", "Run a shell command", RiskLevel.DESTRUCTIVE, """{"command":"rm x"}""")
        }
        result shouldBe RuleVerdict.HIGH_RISK
    }

    test("classify fails safe to HIGH_RISK when the provider exceeds the timeout") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } coAnswers {
            delay(200)
            LLMResponse.Text("LOW_RISK", TokenUsage(10, 1))
        }
        val classifier = LlmRiskClassifier(provider, model = "test-model", timeout = 10.milliseconds)

        val result = runBlocking {
            classifier.classify("bash", "Run a shell command", RiskLevel.DESTRUCTIVE, """{"command":"rm x"}""")
        }
        result shouldBe RuleVerdict.HIGH_RISK
    }
})
