package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.learning.ToolStats
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MutationProposerTest : FunSpec({
    test("proposeMutation asks the model to modify the incumbent config given failure signal") {
        val provider = mockk<LLMProvider>()
        val proposed = HarnessConfig(
            systemPrompt = "Modified prompt addressing edit-nonexistent-file", temperature = 0.7,
            maxTokens = 4096, maxRecalledLessons = 10
        )
        coEvery { provider.complete(any()) } returns LLMResponse.Text(
            Json { encodeDefaults = true }.encodeToString(HarnessConfig.serializer(), proposed), TokenUsage(1, 1)
        )
        val incumbent = HarnessConfig(systemPrompt = "Original prompt", temperature = 0.7, maxTokens = 4096, maxRecalledLessons = 10)

        val challenger = runBlocking {
            proposeMutation(
                provider = provider, model = "test-model", incumbent = incumbent,
                unaddressedFailureModes = listOf("edit-nonexistent-file"), toolStats = emptyMap()
            )
        }

        challenger.systemPrompt shouldContain "edit-nonexistent-file"
        challenger shouldNotBe incumbent
    }

    test("proposeMutation returns the incumbent unchanged if the model's response doesn't parse") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("not json at all", TokenUsage(1, 1))
        val incumbent = HarnessConfig(systemPrompt = "Original prompt")

        val result = runBlocking {
            proposeMutation(provider = provider, model = "test-model", incumbent = incumbent, unaddressedFailureModes = emptyList(), toolStats = emptyMap())
        }

        result shouldBe incumbent
    }

    test("proposeMutation includes tool reliability stats in the prompt it sends") {
        val provider = mockk<LLMProvider>()
        val capturedPrompts = mutableListOf<String>()
        coEvery { provider.complete(any()) } coAnswers {
            capturedPrompts.add(firstArg<dev.sophi.ai.api.CompletionRequest>().messages.first().content)
            LLMResponse.Text(Json { encodeDefaults = true }.encodeToString(HarnessConfig.serializer(), HarnessConfig()), TokenUsage(1, 1))
        }
        val toolStats = mapOf("bash" to ToolStats(attempts = 10, failures = 6, streak = 3, meanDurationMillis = 200, lastErrors = listOf("timeout")))

        runBlocking {
            proposeMutation(provider = provider, model = "test-model", incumbent = HarnessConfig(), unaddressedFailureModes = emptyList(), toolStats = toolStats)
        }

        capturedPrompts.first() shouldContain "bash"
    }
})
