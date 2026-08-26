package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.agent.eval.EvalScenario
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.versioning.ScorecardStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

class EvalSuiteRunnerTest : FunSpec({
    fun stubProvider(): LLMProvider {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        return provider
    }

    test("runSuite aggregates pass/fail across cases into a headline score and per-category breakdown") {
        val cases = listOf(
            EvalCase("c1", "category-a", EvalScenario("c1", "goal", StopCondition.ShellCheck("exit 0"), maxIterations = 1)),
            EvalCase("c2", "category-a", EvalScenario("c2", "goal", StopCondition.ShellCheck("exit 1"), maxIterations = 1)),
            EvalCase("c3", "category-b", EvalScenario("c3", "goal", StopCondition.ShellCheck("exit 0"), maxIterations = 1))
        )
        val scorecardStore = ScorecardStore(createTempDirectory("eval-suite-test"))

        val scorecard = runBlocking {
            runSuite(
                cases = cases, provider = stubProvider(), registry = ToolRegistry(),
                sessionManager = FileSessionManager(createTempDirectory("eval-suite-sessions")),
                contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model", configVersionId = "cfg-1",
                systemPrompt = null, scorecardStore = scorecardStore
            )
        }

        scorecard.totalCases shouldBe 3
        scorecard.headlineScore shouldBe (2.0 / 3.0)
        scorecard.perCategory["category-a"] shouldBe 0.5
        scorecard.perCategory["category-b"] shouldBe 1.0
    }

    test("runSuite persists the resulting Scorecard, retrievable via ScorecardStore.forConfigVersion") {
        val cases = listOf(
            EvalCase("c1", "category-a", EvalScenario("c1", "goal", StopCondition.ShellCheck("exit 0"), maxIterations = 1))
        )
        val scorecardStore = ScorecardStore(createTempDirectory("eval-suite-test"))

        runBlocking {
            runSuite(
                cases = cases, provider = stubProvider(), registry = ToolRegistry(),
                sessionManager = FileSessionManager(createTempDirectory("eval-suite-sessions")),
                contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model", configVersionId = "cfg-1",
                systemPrompt = null, scorecardStore = scorecardStore
            )
        }

        scorecardStore.forConfigVersion("cfg-1") shouldHaveSize 1
    }

    test("runSuite with an empty case list produces a scorecard with zero cases, not a crash") {
        val scorecardStore = ScorecardStore(createTempDirectory("eval-suite-test"))

        val scorecard = runBlocking {
            runSuite(
                cases = emptyList(), provider = stubProvider(), registry = ToolRegistry(),
                sessionManager = FileSessionManager(createTempDirectory("eval-suite-sessions")),
                contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model", configVersionId = "cfg-1",
                systemPrompt = null, scorecardStore = scorecardStore
            )
        }

        scorecard.totalCases shouldBe 0
        scorecard.headlineScore shouldBe 0.0
    }
})
