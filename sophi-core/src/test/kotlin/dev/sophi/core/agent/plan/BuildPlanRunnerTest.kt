package dev.sophi.core.agent.plan

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

class BuildPlanRunnerTest : FunSpec({
    test("buildPlanRunner assembles a runner that plans and executes end to end") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("step done"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )

        val runner = buildPlanRunner(
            provider = provider,
            registry = ToolRegistry(),
            sessionManager = FileSessionManager(createTempDirectory("build-plan-runner-test")),
            config = PlanRunnerConfig(model = "test-model")
        )
        val outcome = runBlocking { runner.run("parent", "ship it", StopCondition.LlmJudged) }

        outcome.finalStatus shouldBe PlanFinalStatus.Met
        outcome.totalSteps shouldBe 1
    }

    test("buildPlanRunner passes the caller's context provider through to the planner") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("step done"))
        val prompts = mutableListOf<String>()
        coEvery { provider.complete(any()) } coAnswers {
            prompts.add(firstArg<dev.sophi.ai.api.CompletionRequest>().messages.first().content)
            when (prompts.size) {
                1 -> LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1))
                2 -> LLMResponse.Text("1.0", TokenUsage(1, 1))
                else -> LLMResponse.Text("YES", TokenUsage(1, 1))
            }
        }

        val runner = buildPlanRunner(
            provider = provider,
            registry = ToolRegistry(),
            sessionManager = FileSessionManager(createTempDirectory("build-plan-runner-ctx")),
            config = PlanRunnerConfig(model = "test-model"),
            contextProvider = { listOf("remember: the user hates long plans") }
        )
        runBlocking { runner.run("parent", "ship it", StopCondition.LlmJudged) }

        prompts.first().contains("the user hates long plans") shouldBe true
    }
})
