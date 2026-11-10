package dev.sophi.schedule.engine

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.dryrun.ChangePlan
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.PlanNotRunnableException
import dev.sophi.core.dryrun.PlanStatus
import dev.sophi.core.dryrun.StepResult
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.notify.NoopNotifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

private fun plan(status: PlanStatus, vararg results: StepResult) =
    ChangePlan("plan_s", 1L, "offboard jane", "s1", reads = emptyList(), steps = emptyList(), status = status, results = results.toList())

class ScheduleEnginePlanTest : FunSpec({

    fun engine(
        provider: LLMProvider,
        registry: ToolRegistry = ToolRegistry(),
        runChangePlan: (suspend (String) -> ChangePlan)? = null,
        planFirst: PlanFirst = PlanFirst.OFF,
        savePendingPlan: ((ChangePlan) -> Unit)? = null,
        grants: Set<String> = emptySet(),
    ): Pair<ScheduleEngine, TaskStore> {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        val e = ScheduleEngine(store, RunLog(home.resolve("runs.jsonl")), provider, registry,
            FileSessionManager(createTempDirectory("schedule-plan-test")), NoopNotifier, model = "m", contextWindowTokens = 100_000,
            runChangePlan = runChangePlan, planFirst = planFirst, savePendingPlan = savePendingPlan)
        return e to store
    }

    fun planTask(store: TaskStore) = store.add(ScheduledTask(
        name = "plan: offboard jane", trigger = Trigger.Once(0L), mode = TaskMode.Recurring, prompt = "offboard jane", changePlanId = "plan_s"))

    test("a task with a change plan replays it instead of running the model") {
        val provider = mockk<LLMProvider>()
        var ran: String? = null
        val (e, store) = engine(provider, runChangePlan = { ran = it; plan(PlanStatus.DONE, StepResult(1, "ok", "done")) })
        val record = runBlocking { e.runNow(planTask(store).id) }!!
        ran shouldBe "plan_s"
        record.outcome shouldBe RunOutcome.Succeeded
        record.summary shouldBe "Plan plan_s: 1 ok"
        verify(exactly = 0) { provider.stream(any()) }
    }

    test("a failed replay is a failed run with the step's error") {
        val (e, store) = engine(mockk(), runChangePlan = {
            plan(PlanStatus.FAILED, StepResult(1, "error", "Error: user not found"), StepResult(2, "not-run", ""))
        })
        val record = runBlocking { e.runNow(planTask(store).id) }!!
        (record.outcome as RunOutcome.Failed).error shouldBe "plan plan_s failed: user not found"
    }

    test("an unapproved change plan is a failed run") {
        val (e, store) = engine(mockk(), runChangePlan = { throw PlanNotRunnableException("This plan wasn't approved") })
        val record = runBlocking { e.runNow(planTask(store).id) }!!
        (record.outcome as RunOutcome.Failed).error shouldBe "This plan wasn't approved"
    }

    test("a change plan with no plan-first runtime is a failed run") {
        val (e, store) = engine(mockk())
        val record = runBlocking { e.runNow(planTask(store).id) }!!
        (record.outcome as RunOutcome.Failed).error shouldContain "plan_s"
    }

    test("a recurring plan-first run saves a pending plan and says so in the summary") {
        val okta = object : Tool {
            var calls = 0
            override val name = "okta__deactivate_user"
            override val description = "fake"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override fun reach(argumentsJson: String) = Reach.OUTSIDE_CHANGE
            override suspend fun execute(argumentsJson: String): String { calls++; return "done" }
        }
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "okta__deactivate_user", "{}"))), StreamEvent.Usage(TokenUsage(1, 0))),
            flowOf(StreamEvent.Content("planned"), StreamEvent.Usage(TokenUsage(1, 1))),
        )
        val saved = mutableListOf<ChangePlan>()
        val (e, store) = engine(provider, ToolRegistry().register(okta), planFirst = PlanFirst.OUTSIDE, savePendingPlan = { saved += it })
        val task = store.add(ScheduledTask(name = "leavers", trigger = Trigger.Interval(3600), mode = TaskMode.Recurring,
            prompt = "offboard today's leavers", toolGrants = setOf("okta__deactivate_user")))
        val record = runBlocking { e.runNow(task.id) }!!

        okta.calls shouldBe 0
        saved.single().steps.single().tool shouldBe "okta__deactivate_user"
        saved.single().task shouldBe "offboard today's leavers"
        record.summary shouldBe "Plan waiting for your approval: 1 change — sophi plans show ${saved.single().id}"
        record.pendingPlanId shouldBe saved.single().id
    }
})
