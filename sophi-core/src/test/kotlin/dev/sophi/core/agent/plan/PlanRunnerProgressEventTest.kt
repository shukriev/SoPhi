package dev.sophi.core.agent.plan

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
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

        events shouldHaveSize 4
        (events[0] as PlanProgressEvent.PlanReady).plan.id shouldBe "plan_1"
        val started = events[1] as PlanProgressEvent.StepStarted
        started.step.id shouldBe "s1"
        val attempt = events[2] as PlanProgressEvent.StepAttempt
        attempt.step.id shouldBe "s1"
        attempt.attempt shouldBe 1
        attempt.model shouldBe "m"
        attempt.childSessionId.isNotBlank() shouldBe true
        val finished = events[3] as PlanProgressEvent.StepFinished
        finished.step.id shouldBe "s1"
        finished.step.status shouldBe StepStatus.Done
    }

    test("no PlanReady is emitted for a caller-supplied initialPlan, and planner.plan is never called") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        val events = mutableListOf<PlanProgressEvent>()

        val runner = PlanRunner(
            agentLoop(provider), FileSessionManager(createTempDirectory("plan-runner-progress-initial")), provider,
            planner, StepCritic { _, _ -> 1.0 }, PlanRunnerConfig(model = "m"),
            onProgress = { events.add(it) }
        )
        val approved = Plan(id = "plan_9", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "do it")))
        val outcome = runBlocking {
            runner.run("parent", "goal", StopCondition.LlmJudged, initialPlan = approved)
        }

        events.none { it is PlanProgressEvent.PlanReady } shouldBe true
        coVerify(exactly = 0) { planner.plan(any(), any()) }
        outcome.planId shouldBe "plan_9"
        outcome.finalStatus shouldBe PlanFinalStatus.Met
    }

    test("the raw onEvent seam still carries a step's turn events, unwrapped, alongside onProgress") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns
            Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "do it")))
        val turnEvents = mutableListOf<TurnEvent>()
        var attemptSeenBeforeFirstToken = false

        val runner = PlanRunner(
            agentLoop(provider), FileSessionManager(createTempDirectory("plan-runner-progress-raw")), provider,
            planner, StepCritic { _, _ -> 1.0 }, PlanRunnerConfig(model = "m"),
            onEvent = { turnEvents.add(it) },
            onProgress = { if (it is PlanProgressEvent.StepAttempt && turnEvents.isEmpty()) attemptSeenBeforeFirstToken = true }
        )
        runBlocking { runner.run("parent", "goal", StopCondition.LlmJudged) }

        turnEvents.filterIsInstance<TurnEvent.Token>().isNotEmpty() shouldBe true
        // The boundary has to land first, or a renderer resets its per-step state mid-stream.
        attemptSeenBeforeFirstToken shouldBe true
    }

    test("a low-confidence step fires Escalating then a second StepAttempt with attempt = 2 and the escalation model") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("attempt"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns
            Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "do it")))
        var judgeCalls = 0
        val events = mutableListOf<PlanProgressEvent>()

        val runner = PlanRunner(
            agentLoop(provider), FileSessionManager(createTempDirectory("plan-runner-progress-esc")), provider,
            planner, StepCritic { _, _ -> judgeCalls++; if (judgeCalls == 1) 0.2 else 0.9 },
            PlanRunnerConfig(model = "cheap", escalationModel = "strong", escalationThreshold = 0.5),
            onProgress = { events.add(it) }
        )
        runBlocking { runner.run("parent", "goal", StopCondition.LlmJudged) }

        val escalating = events.filterIsInstance<PlanProgressEvent.Escalating>().single()
        escalating.toModel shouldBe "strong"
        escalating.confidence shouldBe 0.2
        val attempts = events.filterIsInstance<PlanProgressEvent.StepAttempt>()
        attempts.map { it.attempt } shouldBe listOf(1, 2)
        attempts[0].model shouldBe "cheap"
        attempts[1].model shouldBe "strong"
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
        // Carries the replacement plan itself, not just its id: /goal re-renders the step list
        // and appends the new version to its PlanLog off this event.
        replanned.single().plan.version shouldBe 2
        replanned.single().plan.steps.single().id shouldBe "s1b"
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
        events.filterIsInstance<PlanProgressEvent.PlanReady>() shouldHaveSize 1
    }
})
