package dev.sophi.sdk

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.BackgroundSubagents
import dev.sophi.core.dryrun.ChangePlan
import dev.sophi.core.dryrun.ChangePlanStore
import dev.sophi.core.dryrun.PlanStatus
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory

class BriefingTest : FunSpec({
    val h = 3_600_000L
    val now = 1_700_000_000_000L
    fun task(id: String, name: String, paused: String? = null) =
        ScheduledTask(id = id, name = name, trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p", enabled = paused == null, pausedReason = paused)
    fun run(taskId: String, at: Long, outcome: RunOutcome, summary: String = "", plan: String? = null) =
        RunRecord(taskId, at - 1000, at, outcome, summary, pendingPlanId = plan)
    val waiting = "Plan waiting for your approval: 2 changes — sophi plans show plan_42"
    fun plans(vararg status: Pair<String, PlanStatus>) = ChangePlanStore(createTempDirectory("plans")).apply {
        status.forEach { (id, s) -> save(ChangePlan(id, 0, "t", "s", reads = emptyList(), steps = emptyList(), status = s)) }
    }
    val pending = plans("plan_42" to PlanStatus.PENDING)

    val tasks = listOf(task("t1", "Inbox triage"), task("t2", "Nightly backup", paused = "timed out after #s"), task("t3", "Clean downloads"))
    val runs = listOf(
        run("t1", now - 30 * h, RunOutcome.Succeeded, "too old"),
        run("t1", now - 10 * h, RunOutcome.Succeeded, "Archived 4 newsletters."),
        run("t1", now - 2 * h, RunOutcome.Succeeded, "Archived 12 newsletters, flagged 2 from Ana.\nmore"),
        run("t2", now - 5 * h, RunOutcome.Failed("timed out after 300s"), "Paused: 3 runs in a row failed the same way."),
        run("t3", now - 4 * h, RunOutcome.Succeeded, waiting, plan = "plan_42"),
        run("gone", now - 3 * h, RunOutcome.Skipped("wall-clock budget exceeded")),
    )

    test("outcomes come from the run log in the window, named from the task store, plus finished subagents") {
        val sub = BackgroundSubagents.Info("b1", "s", "researcher", "auth", "sub1", now - h, BackgroundSubagents.Status.DONE, "Auth uses JWT.")
        val running = sub.copy(id = "b2", status = BackgroundSubagents.Status.RUNNING, text = null)
        val out = outcomes(runs, tasks, listOf(sub, running), sinceMs = now - 24 * h, untilMs = now, plans = pending)
        out.map { Triple(it.name, it.status, it.detail.lineSequence().first()) } shouldBe listOf(
            Triple("Inbox triage", OutcomeStatus.DONE, "Archived 4 newsletters."),
            Triple("Nightly backup", OutcomeStatus.FAILED, "timed out after 300s"),
            Triple("Clean downloads", OutcomeStatus.WAITING, waiting),
            Triple("gone", OutcomeStatus.SKIPPED, "wall-clock budget exceeded"),
            Triple("Inbox triage", OutcomeStatus.DONE, "Archived 12 newsletters, flagged 2 from Ana."),
            Triple("researcher", OutcomeStatus.DONE, "Auth uses JWT."),
        )
        out.last().source shouldBe "subagent"
    }

    test("the template groups a task's runs and lists what needs the user") {
        val text = renderBriefing(outcomes(runs, tasks, emptyList(), now - 24 * h, now, pending), tasks, now - 24 * h, now, ZoneOffset.UTC)
        text shouldContain "Done (1)"
        text shouldContain "Inbox triage: 2 runs, last 20:13. Archived 12 newsletters, flagged 2 from Ana."
        text shouldContain "Failed or paused (2)"
        text shouldContain "Nightly backup: failed, 17:13. timed out after 300s"
        text shouldContain "gone: skipped, 19:13. wall-clock budget exceeded"
        text shouldContain "Needs you (2)"
        text shouldContain "Nightly backup is paused (timed out after #s). Fix the cause, then: sophi schedule resume t2"
        text shouldContain "Clean downloads left a plan for your approval: sophi plans show plan_42"
        text shouldNotContain "too old"
    }

    test("a plan approved or cancelled since, or gone, no longer needs the user: the run counts as done") {
        for (store in listOf(plans("plan_42" to PlanStatus.DONE), plans("plan_42" to PlanStatus.CANCELLED), plans(), null)) {
            val out = outcomes(listOf(run("t3", now - h, RunOutcome.Succeeded, waiting, plan = "plan_42")), tasks, emptyList(), now - 24 * h, now, store)
            out.single().status shouldBe OutcomeStatus.DONE
            renderBriefing(out, emptyList(), now - 24 * h, now, ZoneOffset.UTC) shouldNotContain "Needs you"
        }
    }

    test("nothing ran: one line") {
        renderBriefing(emptyList(), emptyList(), now - 24 * h, now, ZoneOffset.UTC) shouldContain "Nothing ran since"
    }

    test("the lede comes from one no-reasoning call; a failing or blank model leaves the template alone") {
        val p = mockk<LLMProvider>()
        val req = mutableListOf<CompletionRequest>()
        coEvery { p.complete(capture(req)) } returns LLMResponse.Text("  Quiet night. Backup needs you.  ", TokenUsage(1, 1))
        runBlocking { withLede("TEMPLATE", p, "local-model") } shouldBe "Quiet night. Backup needs you.\n\nTEMPLATE"
        req.single().model shouldBe "local-model"
        req.single().reasoningEffort shouldBe "none"

        coEvery { p.complete(any()) } throws IllegalStateException("connection refused")
        runBlocking { withLede("TEMPLATE", p, "local-model") } shouldBe "TEMPLATE"
        coEvery { p.complete(any()) } returns LLMResponse.Text(" ", TokenUsage(1, 1))
        runBlocking { withLede("TEMPLATE", p, "local-model") } shouldBe "TEMPLATE"
        runBlocking { withLede("TEMPLATE", null, null) } shouldBe "TEMPLATE"
    }

    test("nothing ran: one line and no model call") {
        val p = mockk<LLMProvider>()
        runBlocking { briefing(createTempDirectory("briefing"), now - h, now, p, "m", zone = ZoneOffset.UTC) } shouldBe "Nothing ran since 21:13."
        coVerify(exactly = 0) { p.complete(any()) }
    }

    test("the window starts at the last briefing, else 24h back, never more than 7 days") {
        val dir = createTempDirectory("briefing")
        briefingWindowStart(dir, now) shouldBe now - 24 * h
        markBriefed(dir, now - 3 * h)
        briefingWindowStart(dir, now) shouldBe now - 3 * h
        markBriefed(dir, now - 30 * 24 * h)
        briefingWindowStart(dir, now) shouldBe now - 7 * 24 * h
    }

    test("briefing(dir) reads the schedule dir's run log and tasks; an unreachable model gives the template alone") {
        val dir = createTempDirectory("briefing")
        val t = TaskStore(dir.resolve("tasks.json")).add(task("x", "Inbox triage"))
        RunLog(dir.resolve("runs.jsonl")).append(run(t.id, now - h, RunOutcome.Succeeded, "Archived 3."))
        val down = mockk<LLMProvider>()
        coEvery { down.complete(any()) } throws java.net.ConnectException("Connection refused")
        val text = runBlocking { briefing(dir, now - 24 * h, now, down, "local-model", zone = ZoneOffset.UTC) }
        text shouldBe "Since Mon 13 Nov 22:13 (24h)\n\nDone (1)\n  - Inbox triage: 21:13. Archived 3."
    }

    test("a watch's unchanged checks are left out; its changes show like any done run") {
        val w = task("w1", "Keyboard price")
        val changed = "${dev.sophi.schedule.engine.PAGE_CHANGED}: https://shop.example/kb (1 added, 1 removed)"
        val watchRuns = listOf(
            run("w1", now - 9 * h, RunOutcome.Succeeded, "${dev.sophi.schedule.engine.WATCH_UNCHANGED}: https://shop.example/kb"),
            run("w1", now - 6 * h, RunOutcome.Succeeded, "$changed\n+ Keyboard 79 EUR"),
            run("w1", now - 3 * h, RunOutcome.Succeeded, "${dev.sophi.schedule.engine.WATCH_UNCHANGED}: https://shop.example/kb"),
        )
        val out = outcomes(watchRuns, listOf(w), emptyList(), now - 24 * h, now)
        out.map { it.atMs } shouldBe listOf(now - 6 * h)
        renderBriefing(out, listOf(w), now - 24 * h, now, ZoneOffset.UTC) shouldContain "Keyboard price: 16:13. $changed"
        outcomes(watchRuns.take(1), listOf(w), emptyList(), now - 24 * h, now) shouldBe emptyList()
    }

    test("a run that had nothing to report is left out of the briefing") {
        val t = task("d1", "Disk alarm")
        val runs = listOf(
            run("d1", now - 9 * h, RunOutcome.Succeeded, dev.sophi.schedule.engine.NOTHING_TO_REPORT),
            run("d1", now - 3 * h, RunOutcome.Succeeded, "Disk usage is 95%"),
        )
        outcomes(runs, listOf(t), emptyList(), now - 24 * h, now).map { it.atMs } shouldBe listOf(now - 3 * h)
    }
})
