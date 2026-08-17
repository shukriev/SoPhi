package dev.sophi.cli.goal

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.cli.LiveRegion
import dev.sophi.cli.ScriptedInputSource
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.plan.Plan
import dev.sophi.core.agent.plan.PlanRunnerConfig
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.agent.plan.PlanStep
import dev.sophi.core.agent.plan.Planner
import dev.sophi.core.agent.plan.StepCritic
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

class GoalControllerTest : FunSpec({
    fun sessionManager(): SessionManager = FileSessionManager(createTempDirectory("goal-controller-test"))

    fun controller(
        provider: LLMProvider,
        planner: Planner,
        sm: SessionManager = sessionManager(),
        inputLines: List<String> = listOf("y"),
        interactive: Boolean = true,
        learning: LearningPlugin? = null,
        output: MutableList<String> = mutableListOf()
    ): Pair<GoalController, MutableList<String>> {
        val loop = AgentLoop(provider, ToolRegistry(), sm, contextWindowTokens = TEST_CONTEXT_WINDOW)
        val gc = GoalController(
            agentLoop = loop, sessionManager = sm, provider = provider, planner = planner,
            critic = StepCritic { _, _ -> 1.0 },
            runnerConfig = PlanRunnerConfig(model = "m", allowParallelSteps = false),
            planLog = PlanLog(tempdir().toPath()),
            input = ScriptedInputSource(inputLines),
            liveRegion = LiveRegion(StringBuilder()) { 80 },
            interactive = interactive, tokenViewKey = 'T', autoExitTokenView = true,
            learning = learning, onTurnSettled = { _, _, _ -> }
        ) { output.add(it) }
        return gc to output
    }

    fun singleStepPlan() = Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "do it")))

    test("declining the preview leaves session.entries empty, never calls planner.plan twice, and returns Declined") {
        val provider = mockk<LLMProvider>()
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        val (gc, _) = controller(provider, planner, inputLines = listOf("n"))
        val session = AgentSession(id = "s1")

        val result = runBlocking { gc.run(session, "do the thing") }

        result.shouldBeInstanceOf<GoalRunResult.Declined>()
        session.entries.isEmpty() shouldBe true
        coVerify(exactly = 1) { planner.plan(any(), any()) }
    }

    test("a met goal appends one USER entry, one replay=false entry per step, one replayed summary entry, and returns Ran") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("all done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        val (gc, output) = controller(provider, planner)
        val session = AgentSession(id = "s1")

        val result = runBlocking { gc.run(session, "do the thing") }

        result.shouldBeInstanceOf<GoalRunResult.Ran>()
        session.entries.map { it.role.name } shouldBe listOf("USER", "ASSISTANT", "ASSISTANT")
        session.entries[1].metadata["replay"] shouldBe "false"
        session.entries[2].content shouldContain "[goal: Met"
        output.any { it.contains("Plan") && it.contains("1 steps") } shouldBe true
    }

    test("PromptBuilder.build after a met goal yields exactly two prompt-visible messages") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("all done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        val (gc, _) = controller(provider, planner)
        val session = AgentSession(id = "s1")

        runBlocking { gc.run(session, "do the thing") }

        dev.sophi.core.prompt.PromptBuilder.build(session.branch()).size shouldBe 2
    }

    test("a planner exception leaves the session untouched, prints planning failed, and returns Declined") {
        val provider = mockk<LLMProvider>()
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } throws RuntimeException("provider down")
        val (gc, output) = controller(provider, planner)
        val session = AgentSession(id = "s1")

        val result = runBlocking { gc.run(session, "do the thing") }

        result.shouldBeInstanceOf<GoalRunResult.Declined>()
        session.entries.isEmpty() shouldBe true
        output.any { it.contains("planning failed") } shouldBe true
    }

    test("a ShellCheck goal is constructed from --check and used as the stop condition") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        val (gc, output) = controller(provider, planner)
        val session = AgentSession(id = "s1")

        runBlocking { gc.run(session, "--check ./no-such-script.sh fix it") }

        output.any { it.contains("shell check") && it.contains("./no-such-script.sh") } shouldBe true
    }

    test("recordPlanOutcome is called once on a met goal when a LearningPlugin is wired") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/p"), model = "m")
        val (gc, _) = controller(provider, planner, learning = learning)
        val session = AgentSession(id = "s1")

        runBlocking { gc.run(session, "do the thing") }
        runBlocking { learning.recordSessionEnd(session.id) }

        val lines = dev.sophi.learning.JsonlLog(home.resolve("session-outcomes.jsonl")).readAll()
        lines.single() shouldContain "goal"
    }

    test("non-interactive input auto-approves the preview without reading a line") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        val (gc, _) = controller(provider, planner, inputLines = emptyList(), interactive = false)
        val session = AgentSession(id = "s1")

        val result = runBlocking { gc.run(session, "do the thing") }
        result.shouldBeInstanceOf<GoalRunResult.Ran>()
    }

    test("ESC mid-goal leaves a [goal interrupted entry, does not throw, and returns Ran(session)") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flow {
            delay(200)
            emit(StreamEvent.Content("too late"))
        }
        val planner = mockk<Planner>()
        coEvery { planner.plan(any(), any()) } returns singleStepPlan()
        val scriptedInput = ScriptedInputSource(listOf("y"))
        val sm = sessionManager()
        val loop = AgentLoop(provider, ToolRegistry(), sm, contextWindowTokens = TEST_CONTEXT_WINDOW)
        val gc = GoalController(
            agentLoop = loop, sessionManager = sm, provider = provider, planner = planner,
            critic = StepCritic { _, _ -> 1.0 },
            runnerConfig = PlanRunnerConfig(model = "m", allowParallelSteps = false),
            planLog = PlanLog(tempdir().toPath()),
            input = scriptedInput,
            liveRegion = LiveRegion(StringBuilder()) { 80 },
            interactive = true, tokenViewKey = 'T', autoExitTokenView = true,
            learning = null, onTurnSettled = { _, _, _ -> }
        ) {}
        val session = AgentSession(id = "s1")

        val result = runBlocking {
            val resultDeferred = async { gc.run(session, "do the thing") }
            delay(20)
            scriptedInput.signalEsc()
            resultDeferred.await()
        }

        result.shouldBeInstanceOf<GoalRunResult.Ran>()
        session.entries.any { it.content.contains("[goal interrupted") } shouldBe true
    }
})
