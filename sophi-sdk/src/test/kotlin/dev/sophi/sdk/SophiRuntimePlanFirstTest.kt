package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.PlanNotRunnableException
import dev.sophi.core.dryrun.PlanStatus
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private class OutsideTool(override val name: String, private val where: Reach = Reach.OUTSIDE_CHANGE) : Tool {
    val calls = mutableListOf<String>()
    override val description = "fake"
    override val parametersJson = "{}"
    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
    override fun reach(argumentsJson: String) = where
    override suspend fun execute(argumentsJson: String): String { calls += argumentsJson; return "done $argumentsJson" }
}

class SophiRuntimePlanFirstTest : FunSpec({
    fun provider(vararg rounds: LLMResponse): LLMProvider {
        var n = 0
        val llm = mockk<LLMProvider>()
        every { llm.stream(any()) } answers { rounds[(n++).coerceAtMost(rounds.size - 1)].toStreamFlow() }
        return llm
    }
    fun use(vararg calls: ToolCall) = LLMResponse.ToolUse(calls.toList(), TokenUsage(1, 0))
    val text = LLMResponse.Text("I plan to deactivate Jane.", TokenUsage(1, 1))

    fun runtime(llm: LLMProvider, vararg tools: Tool, planFirst: Boolean = true, scheduleDir: java.nio.file.Path = tempdir().toPath()): SophiRuntime = RuntimeBuilder().apply {
        schedule(scheduleDir)
        provider = llm
        sessionsDir = tempdir().toPath()
        model = "test-model"
        confirmationPolicy(ConfirmationPolicy.ALLOW_ALL)
        tools.forEach { tool(it) }
        if (planFirst) planFirst(PlanFirst.OUTSIDE, dir = tempdir().toPath())
    }.contextWindowTokens(100_000).build()

    test("a turn that wants an outside change ends with PlanReady and a pending plan on disk") {
        val okta = OutsideTool("okta__deactivate_user")
        val rt = runtime(provider(use(ToolCall("c1", "okta__deactivate_user", """{"id":"00u9"}""")), text), okta)
        val events = mutableListOf<TurnEvent>()
        runBlocking { rt.streamTurn(rt.newSession(), "offboard jane") { events += it } }

        okta.calls shouldBe emptyList()
        val ready = events.filterIsInstance<TurnEvent.PlanReady>().single().plan
        ready.task shouldBe "offboard jane"
        ready.steps.single().args shouldBe """{"id":"00u9"}"""
        rt.plans().single().let { it.id shouldBe ready.id; it.status shouldBe PlanStatus.PENDING }
    }

    test("a turn with no outside change emits no plan") {
        val rt = runtime(provider(text), OutsideTool("okta__x"))
        val events = mutableListOf<TurnEvent>()
        runBlocking { rt.streamTurn(rt.newSession(), "hi") { events += it } }
        events.filterIsInstance<TurnEvent.PlanReady>() shouldBe emptyList()
        rt.plans() shouldBe emptyList()
    }

    test("a cancelled turn saves no plan") {
        var n = 0
        val reached = CompletableDeferred<Unit>()
        val llm = mockk<LLMProvider>()
        every { llm.stream(any()) } answers {
            if (n++ == 0) use(ToolCall("c1", "okta__deactivate_user", "{}")).toStreamFlow()
            else flow { reached.complete(Unit); awaitCancellation() }
        }
        val rt = runtime(llm, OutsideTool("okta__deactivate_user"))
        runBlocking {
            val id = rt.newSession()
            val job = launch { rt.turn(id, "offboard jane") }
            reached.await()
            job.cancelAndJoin()
        }
        rt.plans() shouldBe emptyList()
    }

    test("approve then run executes the step and notes the result in the session") {
        val okta = OutsideTool("okta__deactivate_user")
        val rt = runtime(provider(use(ToolCall("c1", "okta__deactivate_user", """{"id":"00u9"}""")), text), okta)
        val sessionId = runBlocking { rt.newSession().also { rt.turn(it, "offboard jane") } }
        val id = rt.plans().single().id

        rt.approvePlan(id).status shouldBe PlanStatus.APPROVED
        val done = runBlocking { rt.runPlan(id) }

        done.status shouldBe PlanStatus.DONE
        okta.calls shouldBe listOf("""{"id":"00u9"}""")
        val note = rt.sessionManager.load(sessionId).entries.last()
        note.metadata["plan"] shouldBe id
        note.content shouldContain "1 ok"
    }

    test("the replay note lands on the caller's live session, so the next turn sees it") {
        val okta = OutsideTool("okta__deactivate_user")
        val rt = runtime(provider(use(ToolCall("c1", "okta__deactivate_user", "{}")), text), okta)
        val live = runBlocking { rt.streamTurn(rt.sessionManager.load(rt.newSession()), "offboard jane") {} }
        val id = rt.plans().single().id
        rt.approvePlan(id)
        runBlocking { rt.runPlan(id, session = live) }
        live.tip!!.metadata["plan"] shouldBe id
        rt.sessionManager.load(live.id).tip!!.id shouldBe live.tip!!.id
    }

    test("approvePlan with dropped steps runs only the rest") {
        val okta = OutsideTool("okta__deactivate_user")
        val gh = OutsideTool("github__remove_member")
        val rt = runtime(provider(use(ToolCall("c1", "okta__deactivate_user", "{}"), ToolCall("c2", "github__remove_member", "{}")), text), okta, gh)
        runBlocking { rt.turn(rt.newSession(), "offboard jane") }
        val id = rt.plans().single().id
        rt.approvePlan(id, dropped = setOf(1))
        runBlocking { rt.runPlan(id) }
        okta.calls shouldBe emptyList()
        gh.calls.size shouldBe 1
    }

    test("cancelPlan stops a pending plan from running") {
        val rt = runtime(provider(use(ToolCall("c1", "okta__deactivate_user", "{}")), text), OutsideTool("okta__deactivate_user"))
        runBlocking { rt.turn(rt.newSession(), "offboard jane") }
        val id = rt.plans().single().id
        rt.cancelPlan(id).status shouldBe PlanStatus.CANCELLED
        shouldThrow<PlanNotRunnableException> { runBlocking { rt.runPlan(id) } }
    }

    test("without planFirst, plans() throws") {
        val rt = runtime(provider(text), planFirst = false)
        shouldThrow<IllegalStateException> { rt.plans() }.message shouldBe "plan-first isn't enabled on this runtime"
    }

    test("planFirstScope returns the plan recorded inside its block") {
        val okta = OutsideTool("okta__deactivate_user")
        val rt = runtime(provider(use(ToolCall("c1", "okta__deactivate_user", "{}")), text), okta)
        val (_, plan) = runBlocking {
            val id = rt.newSession()
            rt.planFirstScope(id, "goal: offboard jane") { rt.agentLoop.turn(rt.sessionManager.load(id), "offboard", rt.config) }
        }
        plan!!.task shouldBe "goal: offboard jane"
        rt.plans().single().id shouldBe plan.id
    }

    test("schedulePlan writes a one-off task carrying the plan id") {
        val dir = tempdir().toPath()
        val rt = runtime(provider(use(ToolCall("c1", "okta__deactivate_user", "{}")), text), OutsideTool("okta__deactivate_user"), scheduleDir = dir)
        runBlocking { rt.turn(rt.newSession(), "offboard jane") }
        val id = rt.plans().single().id
        rt.approvePlan(id)
        val taskId = rt.schedulePlan(id, 1_900_000_000_000L)
        val task = dev.sophi.schedule.store.TaskStore(dir.resolve("tasks.json")).get(taskId)!!
        task.changePlanId shouldBe id
        task.trigger shouldBe dev.sophi.schedule.model.Trigger.Once(1_900_000_000_000L)
    }

    test("schedulePlan refuses a pending plan") {
        val rt = runtime(provider(use(ToolCall("c1", "okta__deactivate_user", "{}")), text), OutsideTool("okta__deactivate_user"))
        runBlocking { rt.turn(rt.newSession(), "offboard jane") }
        shouldThrow<IllegalStateException> { rt.schedulePlan(rt.plans().single().id, 1L) }
    }
})
