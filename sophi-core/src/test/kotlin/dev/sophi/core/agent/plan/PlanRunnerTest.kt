package dev.sophi.core.agent.plan

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

class PlanRunnerTest : FunSpec({
    fun sessionManager(): SessionManager = FileSessionManager(createTempDirectory("plan-runner-test"))

    fun runner(
        provider: LLMProvider,
        planner: Planner,
        critic: StepCritic = StepCritic { _, _ -> 1.0 },
        config: PlanRunnerConfig = PlanRunnerConfig(model = "m"),
        sm: SessionManager = sessionManager(),
        onPlanComplete: suspend (PlanOutcome) -> Unit = {}
    ): PlanRunner {
        val loop = AgentLoop(provider, ToolRegistry(), sm)
        return PlanRunner(loop, sm, provider, planner, critic, config, onPlanComplete = onPlanComplete)
    }

    fun singleStepPlan(instruction: String = "do it") =
        Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = instruction)))

    test("a single-step plan met by LlmJudged reports Met and calls onPlanComplete once") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        var completedOutcome: PlanOutcome? = null

        val outcome = runBlocking {
            runner(provider, planner, onPlanComplete = { completedOutcome = it })
                .run("parent", "goal", StopCondition.LlmJudged)
        }

        outcome.finalStatus shouldBe PlanFinalStatus.Met
        outcome.totalSteps shouldBe 1
        completedOutcome shouldBe outcome
    }

    test("ShellCheck stop condition is wired through: a failing script keeps the plan Exhausted") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        coEvery { planner.replan(any(), any(), any(), any()) } answers {
            firstArg<Plan>().copy(version = firstArg<Plan>().version + 1)
        }

        val outcome = runBlocking {
            runner(provider, planner, config = PlanRunnerConfig(model = "m", maxReplans = 1))
                .run("parent", "goal", StopCondition.ShellCheck("./no-such-script.sh"))
        }
        outcome.finalStatus shouldBe PlanFinalStatus.Exhausted
    }

    test("independent steps run sequentially when allowParallelSteps is false") {
        val provider = mockk<LLMProvider>()
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        every { provider.stream(any()) } returns flow {
            val now = inFlight.incrementAndGet()
            maxObserved.updateAndGet { maxOf(it, now) }
            delay(30)
            inFlight.decrementAndGet()
            emit(StreamEvent.Content("done"))
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns Plan(
            id = "plan_1", goalPrompt = "goal",
            steps = listOf(PlanStep(id = "s1", instruction = "a"), PlanStep(id = "s2", instruction = "b"))
        )

        runBlocking {
            runner(provider, planner, config = PlanRunnerConfig(model = "m", allowParallelSteps = false))
                .run("parent", "goal", StopCondition.LlmJudged)
        }
        maxObserved.get() shouldBe 1
    }

    test("independent steps run concurrently when allowParallelSteps is true") {
        val provider = mockk<LLMProvider>()
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        every { provider.stream(any()) } returns flow {
            val now = inFlight.incrementAndGet()
            maxObserved.updateAndGet { maxOf(it, now) }
            delay(30)
            inFlight.decrementAndGet()
            emit(StreamEvent.Content("done"))
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns Plan(
            id = "plan_1", goalPrompt = "goal",
            steps = listOf(PlanStep(id = "s1", instruction = "a"), PlanStep(id = "s2", instruction = "b"))
        )

        runBlocking {
            runner(provider, planner, config = PlanRunnerConfig(model = "m", allowParallelSteps = true))
                .run("parent", "goal", StopCondition.LlmJudged)
        }
        (maxObserved.get() >= 2) shouldBe true
    }

    test("a failed step triggers a diff-based replan that keeps Done steps and bumps the version") {
        val provider = mockk<LLMProvider>()
        var streamCall = 0
        every { provider.stream(any()) } answers {
            streamCall++
            when (streamCall) {
                1 -> flowOf(StreamEvent.Content("s1 done"))
                2 -> throw RuntimeException("s2 boom")
                else -> flowOf(StreamEvent.Content("s2 recovered"))
            }
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        val initialPlan = Plan(
            id = "plan_1", goalPrompt = "goal",
            steps = listOf(PlanStep(id = "s1", instruction = "step one"), PlanStep(id = "s2", instruction = "step two"))
        )
        coEvery { planner.plan(any(), any()) } returns initialPlan
        coEvery { planner.replan(any(), "s2", any(), any()) } answers {
            val current = firstArg<Plan>()
            current.copy(
                steps = current.steps.filter { it.status == StepStatus.Done } +
                    PlanStep(id = "s2b", instruction = "retry step two"),
                version = current.version + 1, parentPlanId = current.id
            )
        }

        val outcome = runBlocking { runner(provider, planner).run("parent", "goal", StopCondition.LlmJudged) }
        outcome.finalStatus shouldBe PlanFinalStatus.Met
        outcome.planVersionCount shouldBe 2
    }

    test("a step that keeps failing exhausts maxReplans and reports Exhausted") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } throws RuntimeException("always fails")
        val planner = mockk<Planner>()
        val plan = Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "x")))
        coEvery { planner.plan(any(), any()) } returns plan
        coEvery { planner.replan(any(), any(), any(), any()) } answers {
            val current = firstArg<Plan>()
            current.copy(steps = listOf(PlanStep(id = "s1", instruction = "retry")), version = current.version + 1)
        }

        val outcome = runBlocking {
            runner(provider, planner, config = PlanRunnerConfig(model = "m", maxReplans = 2))
                .run("parent", "goal", StopCondition.LlmJudged)
        }
        outcome.finalStatus shouldBe PlanFinalStatus.Exhausted
        outcome.replans.size shouldBe 2
    }

    test("stop condition unmet after all steps are Done triggers an extend-plan replan rather than false success") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        var judgeCall = 0
        coEvery { provider.complete(any()) } coAnswers {
            judgeCall++
            LLMResponse.Text(if (judgeCall >= 2) "YES" else "NO", TokenUsage(1, 1))
        }
        val planner = mockk<Planner>()
        val plan = Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "step one")))
        coEvery { planner.plan(any(), any()) } returns plan
        coEvery { planner.replan(any(), "s1", any(), any()) } answers {
            val current = firstArg<Plan>()
            current.copy(steps = current.steps + PlanStep(id = "s2", instruction = "additional step"), version = current.version + 1)
        }

        val outcome = runBlocking { runner(provider, planner).run("parent", "goal", StopCondition.LlmJudged) }
        outcome.finalStatus shouldBe PlanFinalStatus.Met
        outcome.planVersionCount shouldBe 2
    }

    test("a low-confidence step escalates once to a stronger model") {
        val provider = mockk<LLMProvider>()
        val capturedModels = mutableListOf<String>()
        every { provider.stream(any()) } answers {
            capturedModels.add(firstArg<CompletionRequest>().model)
            flowOf(StreamEvent.Content("attempt"))
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        var judgeCalls = 0
        val critic = StepCritic { _, _ -> judgeCalls++; if (judgeCalls == 1) 0.2 else 0.9 }

        val outcome = runBlocking {
            runner(provider, planner, critic = critic,
                config = PlanRunnerConfig(model = "cheap-model", escalationModel = "strong-model", escalationThreshold = 0.5))
                .run("parent", "goal", StopCondition.LlmJudged)
        }
        outcome.finalStatus shouldBe PlanFinalStatus.Met
        capturedModels shouldBe listOf("cheap-model", "strong-model")
    }

    test("a step depending on a nonexistent step id triggers a replan instead of looping forever") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        val stuckPlan = Plan(id = "plan_1", goalPrompt = "goal",
            steps = listOf(PlanStep(id = "s1", instruction = "step one", dependsOn = listOf("ghost"))))
        coEvery { planner.plan(any(), any()) } returns stuckPlan
        coEvery { planner.replan(any(), "s1", any(), any()) } answers {
            val current = firstArg<Plan>()
            current.copy(steps = listOf(PlanStep(id = "s1b", instruction = "unblocked")), version = current.version + 1)
        }

        val outcome = runBlocking { runner(provider, planner).run("parent", "goal", StopCondition.LlmJudged) }
        outcome.finalStatus shouldBe PlanFinalStatus.Met
        outcome.planVersionCount shouldBe 2
    }

    test("the run budget caps total agent turns, counting model escalations too") {
        val provider = mockk<LLMProvider>()
        val turns = AtomicInteger(0)
        every { provider.stream(any()) } answers {
            turns.incrementAndGet()
            flowOf(StreamEvent.Content("attempt"))
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("NO", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        coEvery { planner.replan(any(), any(), any(), any()) } answers {
            val current = firstArg<Plan>()
            current.copy(steps = listOf(PlanStep(id = "s1", instruction = "again")), version = current.version + 1)
        }

        val outcome = runBlocking {
            runner(provider, planner,
                config = PlanRunnerConfig(model = "m", maxReplans = 10, maxStepExecutions = 3))
                .run("parent", "goal", StopCondition.LlmJudged)
        }
        outcome.finalStatus shouldBe PlanFinalStatus.Exhausted
        (turns.get() <= 3) shouldBe true
    }

    test("the outcome reports the plan id of the plan that finished") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()

        val outcome = runBlocking { runner(provider, planner).run("parent", "goal", StopCondition.LlmJudged) }
        outcome.planId shouldBe "plan_1"
    }

    test("a step marked decompose is expanded into a sub-plan instead of being executed") {
        val provider = mockk<LLMProvider>()
        val instructions = mutableListOf<String>()
        every { provider.stream(any()) } answers {
            instructions.add(firstArg<CompletionRequest>().messages.last().content)
            flowOf(StreamEvent.Content("sub-step done"))
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan("goal", any()) } returns Plan(
            id = "plan_1", goalPrompt = "goal",
            steps = listOf(PlanStep(id = "s1", instruction = "process every ticket", decompose = true))
        )
        coEvery { planner.plan("process every ticket", any()) } returns Plan(
            id = "plan_2", goalPrompt = "process every ticket",
            steps = listOf(PlanStep(id = "c1", instruction = "ticket one"), PlanStep(id = "c2", instruction = "ticket two"))
        )

        val outcome = runBlocking { runner(provider, planner).run("parent", "goal", StopCondition.LlmJudged) }

        outcome.finalStatus shouldBe PlanFinalStatus.Met
        instructions.none { it.contains("process every ticket") } shouldBe true
        instructions shouldBe listOf("ticket one", "ticket two")
        outcome.decompositions shouldHaveSize 1
        outcome.decompositions.single().stepId shouldBe "s1"
        outcome.decompositions.single().childPlanId shouldBe "plan_2"
        outcome.decompositions.single().childStepCount shouldBe 2
        outcome.decompositions.single().trigger shouldBe DecompositionTrigger.Declared
    }

    test("a sub-plan's output flows into the dependent step's instruction") {
        val provider = mockk<LLMProvider>()
        val instructions = mutableListOf<String>()
        every { provider.stream(any()) } answers {
            instructions.add(firstArg<CompletionRequest>().messages.last().content)
            flowOf(StreamEvent.Content("SUBTREE-RESULT"))
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan("goal", any()) } returns Plan(
            id = "plan_1", goalPrompt = "goal",
            steps = listOf(
                PlanStep(id = "s1", instruction = "big job", decompose = true),
                PlanStep(id = "s2", instruction = "summarise", dependsOn = listOf("s1"))
            )
        )
        coEvery { planner.plan("big job", any()) } returns Plan(
            id = "plan_2", goalPrompt = "big job",
            steps = listOf(PlanStep(id = "c1", instruction = "the only sub-step"))
        )

        runBlocking { runner(provider, planner).run("parent", "goal", StopCondition.LlmJudged) }
        instructions.last() shouldContain "SUBTREE-RESULT"
        instructions.last() shouldContain "summarise"
    }

    test("a sub-plan is planned with the parent goal and the step instruction as context") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        val capturedContext = mutableListOf<List<String>>()
        coEvery { planner.plan("goal", any()) } returns Plan(
            id = "plan_1", goalPrompt = "goal",
            steps = listOf(PlanStep(id = "s1", instruction = "big job", decompose = true))
        )
        coEvery { planner.plan("big job", any()) } answers {
            capturedContext.add(secondArg())
            Plan(id = "plan_2", goalPrompt = "big job", steps = listOf(PlanStep(id = "c1", instruction = "sub")))
        }

        runBlocking { runner(provider, planner).run("parent", "goal", StopCondition.LlmJudged) }
        capturedContext.single() shouldBe listOf("Parent goal: goal", "This sub-plan must satisfy: big job")
    }

    test("a decompose-marked step at the depth cap is executed normally instead of failing") {
        val provider = mockk<LLMProvider>()
        val instructions = mutableListOf<String>()
        every { provider.stream(any()) } answers {
            instructions.add(firstArg<CompletionRequest>().messages.last().content)
            flowOf(StreamEvent.Content("done anyway"))
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns Plan(
            id = "plan_1", goalPrompt = "goal",
            steps = listOf(PlanStep(id = "s1", instruction = "big job", decompose = true))
        )

        val outcome = runBlocking {
            runner(provider, planner, config = PlanRunnerConfig(model = "m", maxPlanDepth = 0))
                .run("parent", "goal", StopCondition.LlmJudged)
        }
        outcome.finalStatus shouldBe PlanFinalStatus.Met
        instructions shouldBe listOf("big job")
        outcome.decompositions shouldHaveSize 0
    }

    test("onPlanComplete fires exactly once even when the run decomposes") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan("goal", any()) } returns Plan(
            id = "plan_1", goalPrompt = "goal",
            steps = listOf(PlanStep(id = "s1", instruction = "big job", decompose = true))
        )
        coEvery { planner.plan("big job", any()) } returns Plan(
            id = "plan_2", goalPrompt = "big job", steps = listOf(PlanStep(id = "c1", instruction = "sub"))
        )
        val completions = AtomicInteger(0)

        runBlocking {
            runner(provider, planner, onPlanComplete = { completions.incrementAndGet() })
                .run("parent", "goal", StopCondition.LlmJudged)
        }
        completions.get() shouldBe 1
    }
})
