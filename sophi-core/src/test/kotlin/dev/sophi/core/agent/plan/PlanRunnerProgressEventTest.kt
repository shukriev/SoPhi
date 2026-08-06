package dev.sophi.core.agent.plan

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

class PlanRunnerProgressEventTest : FunSpec({
    fun agentLoop(provider: LLMProvider) = AgentLoop(
        provider, ToolRegistry(), FileSessionManager(createTempDirectory("plan-runner-progress-test")),
        confirmationPolicy = ConfirmationPolicy.ALLOW_ALL, contextWindowTokens = TEST_CONTEXT_WINDOW
    )

    test("a single-step plan fires StepStarted then StepFinished with the step's final status") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns
            Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "do it")))
        val events = mutableListOf<PlanProgressEvent>()

        val runner = PlanRunner(
            agentLoop(provider), FileSessionManager(createTempDirectory("plan-runner-progress-sm")), provider,
            planner, StepCritic { _, _ -> 1.0 }, PlanRunnerConfig(model = "m"),
            onProgress = { events.add(it) }
        )
        runBlocking { runner.run("parent", "goal", StopCondition.LlmJudged) }

        events shouldHaveSize 2
        val started = events[0] as PlanProgressEvent.StepStarted
        started.step.id shouldBe "s1"
        val finished = events[1] as PlanProgressEvent.StepFinished
        finished.step.id shouldBe "s1"
        finished.step.status shouldBe StepStatus.Done
    }

    test("a failed step that gets replanned fires a Replanned progress event") {
        val provider = mockk<LLMProvider>()
        var streamCall = 0
        every { provider.stream(any()) } answers {
            streamCall++
            if (streamCall == 1) throw RuntimeException("boom") else flowOf(StreamEvent.Content("recovered"))
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns
            Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "do it")))
        coEvery { planner.replan(any(), "s1", any(), any()) } answers {
            firstArg<Plan>().copy(
                steps = listOf(PlanStep(id = "s1b", instruction = "retry")), version = firstArg<Plan>().version + 1
            )
        }
        val events = mutableListOf<PlanProgressEvent>()

        val runner = PlanRunner(
            agentLoop(provider), FileSessionManager(createTempDirectory("plan-runner-progress-sm2")), provider,
            planner, StepCritic { _, _ -> 1.0 }, PlanRunnerConfig(model = "m", maxPlanDepth = 0),
            onProgress = { events.add(it) }
        )
        runBlocking { runner.run("parent", "goal", StopCondition.LlmJudged) }

        val replanned = events.filterIsInstance<PlanProgressEvent.Replanned>()
        replanned shouldHaveSize 1
        replanned.single().stepId shouldBe "s1"
        replanned.single().reason shouldBe "step s1 failed"
    }

    test("a step marked decompose fires a Decomposed progress event") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("sub-step done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan("goal", any()) } returns Plan(
            id = "plan_1", goalPrompt = "goal",
            steps = listOf(PlanStep(id = "s1", instruction = "big job", decompose = true))
        )
        coEvery { planner.plan("big job", any()) } returns Plan(
            id = "plan_2", goalPrompt = "big job", steps = listOf(PlanStep(id = "c1", instruction = "sub"))
        )
        val events = mutableListOf<PlanProgressEvent>()

        val runner = PlanRunner(
            agentLoop(provider), FileSessionManager(createTempDirectory("plan-runner-progress-sm3")), provider,
            planner, StepCritic { _, _ -> 1.0 }, PlanRunnerConfig(model = "m"),
            onProgress = { events.add(it) }
        )
        runBlocking { runner.run("parent", "goal", StopCondition.LlmJudged) }

        val decomposed = events.filterIsInstance<PlanProgressEvent.Decomposed>()
        decomposed shouldHaveSize 1
        decomposed.single().stepId shouldBe "s1"
        decomposed.single().childPlanId shouldBe "plan_2"
    }

    test("buildPlanRunner threads onProgress through to the assembled runner") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )
        val events = mutableListOf<PlanProgressEvent>()

        val runner = buildPlanRunner(
            provider = provider, registry = ToolRegistry(),
            sessionManager = FileSessionManager(createTempDirectory("build-plan-runner-progress")),
            config = PlanRunnerConfig(model = "test-model"), contextWindowTokens = TEST_CONTEXT_WINDOW,
            onProgress = { events.add(it) }
        )
        runBlocking { runner.run("parent", "ship it", StopCondition.LlmJudged) }

        events.filterIsInstance<PlanProgressEvent.StepStarted>() shouldHaveSize 1
    }
})
