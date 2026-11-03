package dev.sophi.cli.goal

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.cli.LiveRegion
import dev.sophi.cli.ScriptedInputSource
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.session.AgentSession
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.undo.FileRestorer
import dev.sophi.learning.LearningConfig
import dev.sophi.sdk.GoalResult
import dev.sophi.sdk.GoalStatus
import dev.sophi.sdk.RuntimeBuilder
import dev.sophi.sdk.SophiRuntime
import dev.sophi.sdk.runGoal
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.exists
import kotlin.io.path.writeText

private const val TEST_CONTEXT_WINDOW = 100_000

class GoalControllerTest : FunSpec({
    /** The runtime /goal runs on; [setup] adds tools, plan-first, checkpoints, learning. */
    fun runtime(provider: LLMProvider, setup: RuntimeBuilder.() -> Unit = {}): SophiRuntime = RuntimeBuilder().apply {
        this.provider = provider; sessionsDir = tempdir().toPath(); model = "m"
        confirmationPolicy(ConfirmationPolicy.ALLOW_ALL)
        setup()
    }.contextWindowTokens(TEST_CONTEXT_WINDOW).build()

    /** Answers the planner with a one-step plan and every other completion (critic, judge) with [verdict]. */
    fun LLMProvider.completes(verdict: String) {
        coEvery { complete(any()) } answers {
            val prompt = firstArg<CompletionRequest>().messages.joinToString { it.content }
            val reply = if ("\"steps\"" in prompt) """{"steps":[{"id":"s1","instruction":"do it"}]}""" else verdict
            LLMResponse.Text(reply, TokenUsage(1, 1))
        }
    }

    fun controller(
        runtime: SophiRuntime,
        input: ScriptedInputSource = ScriptedInputSource(listOf("y")),
        interactive: Boolean = true,
        output: MutableList<String> = mutableListOf()
    ): Pair<GoalController, MutableList<String>> {
        val gc = GoalController(
            runtime, PlanLog(tempdir().toPath()),
            input = input,
            liveRegion = LiveRegion(StringBuilder()) { 80 },
            interactive = interactive, tokenViewKey = 'T', autoExitTokenView = true,
            planFirst = runtime.planFirst?.let { dev.sophi.cli.PlanFirstHost(runtime, input) { output.add(it) } },
        ) { output.add(it) }
        return gc to output
    }

    test("a /goal whose critic keeps rejecting the stubbed step still plans each change once") {
        val okta = object : dev.sophi.core.tools.Tool {
            override val name = "okta__deactivate_user"
            override val description = "fake"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override fun reach(argumentsJson: String) = dev.sophi.core.tools.Reach.OUTSIDE_CHANGE
            override suspend fun execute(argumentsJson: String) = "done"
        }
        val provider = mockk<LLMProvider>()
        var n = 0
        every { provider.stream(any()) } answers {
            if (n++ % 2 == 0) flowOf(StreamEvent.ToolCallsReady(listOf(dev.sophi.ai.api.ToolCall("c$n", "okta__deactivate_user", """{"id":"00u9"}"""))))
            else flowOf(StreamEvent.Content("recorded"))
        }
        provider.completes("0") // the critic's zero confidence; the judge's not-YES
        val runtime = runtime(provider) {
            tool(okta)
            planFirst(dev.sophi.core.dryrun.PlanFirst.OUTSIDE, dir = tempdir().toPath())
        }
        val (gc, _) = controller(runtime, ScriptedInputSource(listOf("y", "s")))

        runBlocking { gc.run(AgentSession(id = "s1"), "offboard jane") }

        (n > 2) shouldBe true // the step really was re-run
        runtime.plans().single().steps.size shouldBe 1
    }

    test("a /goal that records changes ends with the review") {
        val okta = object : dev.sophi.core.tools.Tool {
            var calls = 0
            override val name = "okta__deactivate_user"
            override val description = "fake"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override fun reach(argumentsJson: String) = dev.sophi.core.tools.Reach.OUTSIDE_CHANGE
            override suspend fun execute(argumentsJson: String): String { calls++; return "done" }
        }
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(dev.sophi.ai.api.ToolCall("c1", "okta__deactivate_user", "{}")))),
            flowOf(StreamEvent.Content("planned")),
        )
        provider.completes("YES")
        val runtime = runtime(provider) {
            tool(okta)
            planFirst(dev.sophi.core.dryrun.PlanFirst.OUTSIDE, dir = tempdir().toPath())
        }
        val (gc, output) = controller(runtime, ScriptedInputSource(listOf("y", "n")))

        runBlocking { gc.run(AgentSession(id = "s1"), "offboard jane") }

        okta.calls shouldBe 0
        output.any { it.startsWith("Allow okta deactivate_user") } shouldBe true
        runtime.plans().single().status shouldBe dev.sophi.core.dryrun.PlanStatus.CANCELLED
    }

    test("declining the preview leaves session.entries empty, never calls planner.plan twice, and returns Declined") {
        val provider = mockk<LLMProvider>()
        provider.completes("YES")
        val (gc, _) = controller(runtime(provider), ScriptedInputSource(listOf("n")))
        val session = AgentSession(id = "s1")

        val result = runBlocking { gc.run(session, "do the thing") }

        result.shouldBeInstanceOf<GoalRunResult.Declined>()
        session.entries.isEmpty() shouldBe true
        coVerify(exactly = 1) { provider.complete(any()) }
    }

    test("a met goal appends one USER entry, one replay=false entry per step, one replayed summary entry, and returns Ran") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("all done"))
        provider.completes("YES")
        val (gc, output) = controller(runtime(provider))
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
        provider.completes("YES")
        val (gc, _) = controller(runtime(provider))
        val session = AgentSession(id = "s1")

        runBlocking { gc.run(session, "do the thing") }

        dev.sophi.core.prompt.PromptBuilder.build(session.branch()).size shouldBe 2
    }

    test("a planner exception leaves the session untouched, prints planning failed, and returns Declined") {
        // LlmPlanner falls back to a one-step plan rather than throw, so the failure is stubbed at runGoal itself.
        mockkStatic("dev.sophi.sdk.GoalRunKt")
        val runtime = runtime(mockk())
        coEvery { runtime.runGoal(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GoalResult(GoalStatus.Failed, "", RuntimeException("provider down"))
        val (gc, output) = controller(runtime)
        val session = AgentSession(id = "s1")

        val result = runBlocking { gc.run(session, "do the thing") }
        unmockkStatic("dev.sophi.sdk.GoalRunKt")

        result.shouldBeInstanceOf<GoalRunResult.Declined>()
        session.entries.isEmpty() shouldBe true
        output.any { it.contains("planning failed") } shouldBe true
    }

    test("a ShellCheck goal is constructed from --check and used as the stop condition") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        provider.completes("YES")
        val (gc, output) = controller(runtime(provider))
        val session = AgentSession(id = "s1")

        runBlocking { gc.run(session, "--check ./no-such-script.sh fix it") }

        output.any { it.contains("shell check") && it.contains("./no-such-script.sh") } shouldBe true
    }

    test("recordPlanOutcome is called once on a met goal when a LearningPlugin is wired") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        provider.completes("YES")
        val home = tempdir().toPath()
        val runtime = runtime(provider) { learning(LearningConfig(home = home, scope = "/p")) }
        val (gc, _) = controller(runtime)
        val session = AgentSession(id = "s1")

        runBlocking { gc.run(session, "do the thing") }
        runBlocking { runtime.learningPlugin!!.recordSessionEnd(session.id) }

        val lines = dev.sophi.learning.JsonlLog(home.resolve("session-outcomes.jsonl")).readAll()
        // AFTER_TURN also writes "open" rows (the steps' own turns, the settled goal); the closed one carries the note.
        lines.single { "\"outcome\":\"completed\"" in it } shouldContain "goal"
    }

    test("non-interactive input auto-approves the preview without reading a line") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        provider.completes("YES")
        val (gc, _) = controller(runtime(provider), ScriptedInputSource(emptyList()), interactive = false)
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
        provider.completes("YES")
        val scriptedInput = ScriptedInputSource(listOf("y"))
        val (gc, _) = controller(runtime(provider), scriptedInput)
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

    test("/undo right after a goal reverses the files its steps wrote") {
        val root = tempdir().toPath()
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(dev.sophi.ai.api.ToolCall("c1", "write_file", """{"path":"x.txt","content":"hi"}""")))),
            flowOf(StreamEvent.Content("done"))
        )
        provider.completes("YES")
        val runtime = runtime(provider) { builtinTools(root); checkpoints(home = tempdir().toPath()) }
        val store = runtime.checkpointStore!!
        val (gc, _) = controller(runtime)
        // a chat round before the goal, as AgentLoop records it: checkpoint taken, then chat.txt written
        val session = AgentSession(id = "s1")
        val chatCheckpoint = store.snapshot()!!
        root.resolve("chat.txt").writeText("chat")
        session.append(dev.sophi.core.session.EntryRole.ASSISTANT, "wrote chat.txt", mapOf("toolCalls" to "[]", "checkpoint" to chatCheckpoint))
        runBlocking { runtime.sessionManager.save(session) }

        runBlocking { gc.run(session, "write x") }
        root.resolve("x.txt").exists() shouldBe true

        // /undo works on the session as saved on disk
        val saved = runBlocking { runtime.sessionManager.load("s1") }
        val plan = FileRestorer(store).plan(saved)!!
        plan.targetEntryId shouldBe saved.entries.single { it.content == "[goal file changes]" }.id
        FileRestorer(store).apply(saved, plan)
        root.resolve("x.txt").exists() shouldBe false
        root.resolve("chat.txt").exists() shouldBe true
    }

    test("a goal step's bash command that reaches outside the workspace shows up in /undo's warnings") {
        val root = tempdir().toPath()
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(dev.sophi.ai.api.ToolCall("c1", "bash", """{"command":"cat /etc/hosts > out.txt"}""")))),
            flowOf(StreamEvent.Content("done"))
        )
        provider.completes("YES")
        val runtime = runtime(provider) { builtinTools(root); checkpoints(home = tempdir().toPath()) }
        val (gc, _) = controller(runtime)
        val session = AgentSession(id = "s1")

        runBlocking { gc.run(session, "copy hosts") }

        FileRestorer(runtime.checkpointStore!!).plan(session)!!.warnings shouldBe listOf("cat /etc/hosts > out.txt")
    }
})
