package dev.sophi.schedule.tools

import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneId

private fun epochMs(iso: String): Long =
    LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

class ScheduleTaskToolTest : FunSpec({
    fun store(): TaskStore = TaskStore(tempdir().toPath().resolve("tasks.json"))
    fun runLog(): RunLog = RunLog(tempdir().toPath().resolve("runs.jsonl"))

    test("name is manage_scheduled_task") {
        ScheduleTaskTool(store(), runLog()).name shouldContain "manage_scheduled_task"
    }

    test("create with trigger_type=interval and mode=recurring persists a task") {
        val s = store()
        val tool = ScheduleTaskTool(s, runLog())
        val result = runBlocking {
            tool.execute("""{"action":"create","name":"monitor","prompt":"check my feed","trigger_type":"interval","every_seconds":3600,"mode":"recurring"}""")
        }
        result shouldContain "Created task"
        val task = s.list().single()
        task.name shouldContain "monitor"
        (task.trigger is Trigger.Interval) shouldBe true
    }

    test("create with trigger_type=once persists a Trigger.Once with the parsed epoch") {
        val s = store()
        val tool = ScheduleTaskTool(s, runLog())
        val result = runBlocking {
            tool.execute(
                """{"action":"create","name":"reminder","prompt":"ping me","trigger_type":"once","at":"2026-08-01T09:00:00","mode":"recurring"}"""
            )
        }
        result shouldContain "Created task"
        val trigger = s.list().single().trigger as Trigger.Once
        trigger.atMs shouldBe epochMs("2026-08-01T09:00:00")
    }

    test("create with trigger_type=once and an invalid 'at' returns an Error string") {
        val s = store()
        val result = runBlocking {
            ScheduleTaskTool(s, runLog()).execute(
                """{"action":"create","name":"reminder","prompt":"ping me","trigger_type":"once","at":"not-a-date","mode":"recurring"}"""
            )
        }
        result shouldContain "Error"
        s.list() shouldBe emptyList()
    }

    test("create with mode=goal and stop_condition_type=shell_check persists a Goal task") {
        val s = store()
        val tool = ScheduleTaskTool(s, runLog())
        runBlocking {
            tool.execute("""{"action":"create","name":"g","prompt":"fix it","trigger_type":"manual","mode":"goal","stop_condition_type":"shell_check","shell_command":"./t.sh","max_iterations":3}""")
        }
        s.list().single().name shouldContain "g"
    }

    test("create without required fields returns an Error string and does not persist") {
        val s = store()
        val result = runBlocking {
            ScheduleTaskTool(s, runLog()).execute("""{"action":"create","trigger_type":"interval","every_seconds":60,"mode":"recurring"}""")
        }
        result shouldContain "Error"
        s.list() shouldBe emptyList()
    }

    test("list renders enabled/paused state, mode, and the trigger interval") {
        val s = store()
        s.add(ScheduledTask(name = "t", trigger = Trigger.Interval(900), mode = TaskMode.Recurring, prompt = "p"))
        val result = runBlocking { ScheduleTaskTool(s, runLog()).execute("""{"action":"list"}""") }
        result shouldContain "enabled"
        result shouldContain "t"
        result shouldContain "every 900s"
    }

    test("list shows Manual/Once triggers without a bogus interval") {
        val s = store()
        s.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val result = runBlocking { ScheduleTaskTool(s, runLog()).execute("""{"action":"list"}""") }
        result shouldContain "manual"
    }

    test("pause and resume round-trip via task_id") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        val tool = ScheduleTaskTool(s, runLog())
        runBlocking { tool.execute("""{"action":"pause","task_id":"${task.id}"}""") }
        s.get(task.id)!!.enabled shouldBe false
        runBlocking { tool.execute("""{"action":"resume","task_id":"${task.id}"}""") }
        s.get(task.id)!!.enabled shouldBe true
    }

    test("remove deletes the task; unknown id returns an Error string") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val tool = ScheduleTaskTool(s, runLog())
        val ok = runBlocking { tool.execute("""{"action":"remove","task_id":"${task.id}"}""") }
        ok shouldContain "Removed"
        val err = runBlocking { tool.execute("""{"action":"remove","task_id":"no-such-id"}""") }
        err shouldContain "Error"
    }

    test("unknown action returns an Error string") {
        val result = runBlocking { ScheduleTaskTool(store(), runLog()).execute("""{"action":"bogus"}""") }
        result shouldContain "Error"
    }

    // ── update ──────────────────────────────────────────────────────────────

    test("update changes the prompt without touching other fields") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "old"))
        val tool = ScheduleTaskTool(s, runLog())
        val result = runBlocking { tool.execute("""{"action":"update","task_id":"${task.id}","prompt":"new"}""") }
        result shouldContain "Updated"
        val updated = s.get(task.id)!!
        updated.prompt shouldBe "new"
        updated.name shouldBe "t"
    }

    test("update changes the trigger interval and reschedules") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        val tool = ScheduleTaskTool(s, runLog())
        runBlocking { tool.execute("""{"action":"update","task_id":"${task.id}","trigger_type":"interval","every_seconds":3600}""") }
        val updated = s.get(task.id)!!
        (updated.trigger as Trigger.Interval).everySeconds shouldBe 3600
    }

    test("update changes trigger_type=once's 'at' value") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val tool = ScheduleTaskTool(s, runLog())
        runBlocking {
            tool.execute("""{"action":"update","task_id":"${task.id}","trigger_type":"once","at":"2026-09-01T10:00:00"}""")
        }
        val trigger = s.get(task.id)!!.trigger as Trigger.Once
        trigger.atMs shouldBe epochMs("2026-09-01T10:00:00")
    }

    test("riskLevel is SAFE for create with no tool_grants") {
        val tool = ScheduleTaskTool(store(), runLog())
        tool.riskLevel("""{"action":"create","name":"t","prompt":"p","trigger_type":"manual","mode":"recurring"}""") shouldBe
            dev.sophi.core.tools.RiskLevel.SAFE
    }

    test("riskLevel is DESTRUCTIVE for create with a non-empty tool_grants") {
        val tool = ScheduleTaskTool(store(), runLog())
        tool.riskLevel("""{"action":"create","name":"t","prompt":"p","trigger_type":"manual","mode":"recurring","tool_grants":["bash"]}""") shouldBe
            dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
    }

    test("riskLevel is DESTRUCTIVE for update with a non-empty tool_grants") {
        val tool = ScheduleTaskTool(store(), runLog())
        tool.riskLevel("""{"action":"update","task_id":"t1","tool_grants":["bash"]}""") shouldBe
            dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
    }

    test("riskLevel is SAFE for list, pause, resume, remove, and runs regardless of tool_grants") {
        val tool = ScheduleTaskTool(store(), runLog())
        tool.riskLevel("""{"action":"list"}""") shouldBe dev.sophi.core.tools.RiskLevel.SAFE
        tool.riskLevel("""{"action":"pause","task_id":"t1"}""") shouldBe dev.sophi.core.tools.RiskLevel.SAFE
        tool.riskLevel("""{"action":"remove","task_id":"t1"}""") shouldBe dev.sophi.core.tools.RiskLevel.SAFE
    }

    test("ruleVerdict is LOW_RISK for create with no tool_grants") {
        val tool = ScheduleTaskTool(store(), runLog())
        tool.ruleVerdict("""{"action":"create","name":"t","prompt":"p","trigger_type":"manual","mode":"recurring"}""") shouldBe
            dev.sophi.core.tools.RuleVerdict.LOW_RISK
    }

    test("ruleVerdict is HIGH_RISK for create with a non-empty tool_grants") {
        val tool = ScheduleTaskTool(store(), runLog())
        tool.ruleVerdict("""{"action":"create","name":"t","prompt":"p","trigger_type":"manual","mode":"recurring","tool_grants":["bash"]}""") shouldBe
            dev.sophi.core.tools.RuleVerdict.HIGH_RISK
    }

    test("ruleVerdict is HIGH_RISK when arguments cannot be parsed") {
        val tool = ScheduleTaskTool(store(), runLog())
        tool.ruleVerdict("not json") shouldBe dev.sophi.core.tools.RuleVerdict.HIGH_RISK
    }

    test("riskLevel is DESTRUCTIVE, not SAFE, when arguments cannot be parsed") {
        // Fails closed to match ruleVerdict's own convention above: a caller that can't supply
        // real arguments (e.g. a grants-eligibility probe using placeholder JSON) must not be
        // able to read an unparseable call as harmless just because action is missing.
        val tool = ScheduleTaskTool(store(), runLog())
        tool.riskLevel("not json") shouldBe dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
        tool.riskLevel("{}") shouldBe dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
    }

    test("update using tool_grants persists it under the renamed field") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val tool = ScheduleTaskTool(s, runLog())
        runBlocking {
            tool.execute("""{"action":"update","task_id":"${task.id}","tool_grants":["write_file"]}""")
        }
        s.get(task.id)!!.toolGrants shouldBe setOf("write_file")
    }

    test("update without task_id returns an Error string") {
        val result = runBlocking { ScheduleTaskTool(store(), runLog()).execute("""{"action":"update","prompt":"x"}""") }
        result shouldContain "Error"
    }

    test("update with an unknown task_id returns an Error string") {
        val result = runBlocking {
            ScheduleTaskTool(store(), runLog()).execute("""{"action":"update","task_id":"no-such-id","prompt":"x"}""")
        }
        result shouldContain "Error"
    }

    // ── runs ────────────────────────────────────────────────────────────────

    test("runs shows outcome and duration for a task's run history") {
        val s = store()
        val log = runLog()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        log.append(RunRecord(task.id, startedAtMs = 1_000L, finishedAtMs = 4_500L, outcome = RunOutcome.Succeeded, summary = "ok"))
        val tool = ScheduleTaskTool(s, log)
        val result = runBlocking { tool.execute("""{"action":"runs","task_id":"${task.id}"}""") }
        result shouldContain "Succeeded"
        result shouldContain "3.5s"
    }

    test("runs shows 'No run history.' when a task has never run") {
        val s = store()
        val task = s.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val result = runBlocking { ScheduleTaskTool(s, runLog()).execute("""{"action":"runs","task_id":"${task.id}"}""") }
        result shouldContain "No run history"
    }

    test("runs without task_id shows recent runs across all tasks") {
        val s = store()
        val log = runLog()
        log.append(RunRecord("t1", startedAtMs = 0L, finishedAtMs = 1000L, outcome = RunOutcome.Succeeded, summary = "a"))
        log.append(RunRecord("t2", startedAtMs = 0L, finishedAtMs = 2000L, outcome = RunOutcome.Succeeded, summary = "b"))
        val result = runBlocking { ScheduleTaskTool(s, log).execute("""{"action":"runs"}""") }
        result shouldContain "t1"
        result shouldContain "t2"
    }

    // ── cron ────────────────────────────────────────────────────────────────

    test("create with trigger_type=cron and a valid cron_expression persists a task") {
        val s = store()
        val tool = ScheduleTaskTool(s, runLog())
        val result = runBlocking {
            tool.execute("""{"action":"create","name":"daily","prompt":"summarize","trigger_type":"cron","cron_expression":"0 9 * * *","mode":"recurring"}""")
        }
        result shouldContain "Created task"
        val task = s.list().single()
        (task.trigger as Trigger.Cron).expression shouldBe "0 9 * * *"
    }

    test("create with trigger_type=cron and an invalid cron_expression returns an Error string and does not persist") {
        val s = store()
        val result = runBlocking {
            ScheduleTaskTool(s, runLog()).execute("""{"action":"create","name":"bad","prompt":"p","trigger_type":"cron","cron_expression":"not a cron","mode":"recurring"}""")
        }
        result shouldContain "Error: invalid cron expression"
        s.list() shouldBe emptyList()
    }

    test("create with trigger_type=cron and no cron_expression returns an Error string") {
        val result = runBlocking {
            ScheduleTaskTool(store(), runLog()).execute("""{"action":"create","name":"t","prompt":"p","trigger_type":"cron","mode":"recurring"}""")
        }
        result shouldContain "'cron_expression' is required"
    }

    test("list renders a Cron trigger's expression") {
        val s = store()
        s.add(ScheduledTask(name = "t", trigger = Trigger.Cron("0 9 * * *"), mode = TaskMode.Recurring, prompt = "p"))
        val result = runBlocking { ScheduleTaskTool(s, runLog()).execute("""{"action":"list"}""") }
        result shouldContain "cron '0 9 * * *'"
    }
})
