package dev.sophi.core.agent.eval

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.plan.PlanFinalStatus
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

class EvalScenarioTest : FunSpec({
    fun stubProvider(): LLMProvider {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("step done"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1))
        )
        return provider
    }

    test("runEvalScenario reports Met when the real shell check passes") {
        val scenario = EvalScenario(
            name = "trivial-pass",
            goalPrompt = "do it",
            check = StopCondition.ShellCheck(command = "exit 0")
        )

        val outcome = runBlocking {
            runEvalScenario(
                provider = stubProvider(),
                registry = ToolRegistry(),
                sessionManager = FileSessionManager(createTempDirectory("eval-scenario-pass")),
                contextWindowTokens = TEST_CONTEXT_WINDOW,
                model = "test-model",
                scenario = scenario
            )
        }

        outcome.finalStatus shouldBe PlanFinalStatus.Met
    }

    test("runEvalScenario reports Exhausted when the real shell check keeps failing") {
        val scenario = EvalScenario(
            name = "trivial-fail",
            goalPrompt = "do it",
            check = StopCondition.ShellCheck(command = "exit 1"),
            maxIterations = 1
        )

        val outcome = runBlocking {
            runEvalScenario(
                provider = stubProvider(),
                registry = ToolRegistry(),
                sessionManager = FileSessionManager(createTempDirectory("eval-scenario-fail")),
                contextWindowTokens = TEST_CONTEXT_WINDOW,
                model = "test-model",
                scenario = scenario
            )
        }

        outcome.finalStatus shouldBe PlanFinalStatus.Exhausted
    }

    test("runEvalScenario injects the given systemPrompt into the underlying completion requests") {
        val provider = mockk<LLMProvider>()
        val captured = slot<CompletionRequest>()
        every { provider.stream(capture(captured)) } returns flowOf(StreamEvent.Content("step done"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1))
        )
        val scenario = EvalScenario(
            name = "with-prompt", goalPrompt = "do it", check = StopCondition.ShellCheck(command = "exit 0")
        )

        runBlocking {
            runEvalScenario(
                provider = provider, registry = ToolRegistry(),
                sessionManager = FileSessionManager(createTempDirectory("eval-scenario-prompt")),
                contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model",
                scenario = scenario, systemPrompt = "Custom eval system prompt"
            )
        }

        captured.captured.systemPrompt shouldBe "Custom eval system prompt"
    }

    test("runEvalScenario with no systemPrompt behaves exactly as before (null, not a forced default)") {
        val provider = mockk<LLMProvider>()
        val captured = slot<CompletionRequest>()
        every { provider.stream(capture(captured)) } returns flowOf(StreamEvent.Content("step done"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1))
        )
        val scenario = EvalScenario(
            name = "no-prompt", goalPrompt = "do it", check = StopCondition.ShellCheck(command = "exit 0")
        )

        runBlocking {
            runEvalScenario(
                provider = provider, registry = ToolRegistry(),
                sessionManager = FileSessionManager(createTempDirectory("eval-scenario-no-prompt")),
                contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model", scenario = scenario
            )
        }

        captured.captured.systemPrompt shouldBe null
    }
})
