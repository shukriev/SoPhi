package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.agent.eval.EvalScenario
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.versioning.ScorecardStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

class EvalsCommandTest : FunSpec({
    test("EvalsRun prints the headline score and per-category breakdown") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        val cases = listOf(
            EvalCase("c1", "category-a", EvalScenario("c1", "goal", StopCondition.ShellCheck("exit 0"), maxIterations = 1))
        )
        val scorecardStore = ScorecardStore(createTempDirectory("evals-cli-test"))
        val lines = mutableListOf<String>()

        EvalsRun(
            cases = cases, provider = provider, registry = ToolRegistry(),
            sessionManager = FileSessionManager(createTempDirectory("evals-cli-sessions")),
            contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model", configVersionId = "cfg-1",
            systemPrompt = null, scorecardStore = scorecardStore
        ) { lines.add(it) }.run()

        lines.joinToString("\n") shouldContain "headline"
        lines.joinToString("\n") shouldContain "category-a"
    }

    test("EvalsRun reports 'no cases' when the case list is empty, without attempting a run") {
        val lines = mutableListOf<String>()

        EvalsRun(
            cases = emptyList(), provider = mockk<LLMProvider>(), registry = ToolRegistry(),
            sessionManager = FileSessionManager(createTempDirectory("evals-cli-empty")),
            contextWindowTokens = TEST_CONTEXT_WINDOW, model = "test-model", configVersionId = "cfg-1",
            systemPrompt = null, scorecardStore = ScorecardStore(createTempDirectory("evals-cli-empty-scores"))
        ) { lines.add(it) }.run()

        lines.first() shouldContain "No eval cases"
    }
})
