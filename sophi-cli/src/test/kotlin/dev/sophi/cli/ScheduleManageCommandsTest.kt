package dev.sophi.cli

import com.github.ajalt.clikt.testing.test
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

class ScheduleManageCommandsTest : FunSpec({
    test("ScheduleList renders task id, state, and name") {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        store.add(ScheduledTask(name = "monitor", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val out = StringBuilder()
        ScheduleList(home) { out.appendLine(it) }.run()
        out.toString() shouldContain "monitor"
        out.toString() shouldContain "enabled"
    }

    test("ScheduleList reports when there are no tasks") {
        val home = tempdir().toPath()
        val out = StringBuilder()
        ScheduleList(home) { out.appendLine(it) }.run()
        out.toString() shouldContain "No scheduled tasks"
    }

    test("SchedulePause disables a task; SchedulePause on unknown id reports failure") {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        val task = store.add(ScheduledTask(name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        val out = StringBuilder()
        SchedulePause(home, task.id) { out.appendLine(it) }.run()
        store.get(task.id)!!.enabled shouldBe false
        val out2 = StringBuilder()
        SchedulePause(home, "no-such-id") { out2.appendLine(it) }.run()
        out2.toString() shouldContain "No task found"
    }

    test("ScheduleResume re-enables a task") {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        val task = store.add(ScheduledTask(name = "t", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        store.setEnabled(task.id, false)
        val out = StringBuilder()
        ScheduleResume(home, task.id) { out.appendLine(it) }.run()
        store.get(task.id)!!.enabled shouldBe true
    }

    test("ScheduleRemove deletes a task; reports failure for unknown id") {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        val task = store.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val out = StringBuilder()
        ScheduleRemove(home, task.id) { out.appendLine(it) }.run()
        store.list() shouldBe emptyList()
        val out2 = StringBuilder()
        ScheduleRemove(home, "no-such-id") { out2.appendLine(it) }.run()
        out2.toString() shouldContain "No task found"
    }

    test("ScheduleLog renders run records, optionally filtered by task id") {
        val home = tempdir().toPath()
        val log = RunLog(home.resolve("runs.jsonl"))
        log.append(RunRecord("t1", 1L, 2L, RunOutcome.Succeeded, "did stuff"))
        log.append(RunRecord("t2", 3L, 4L, RunOutcome.Succeeded, "other"))
        val out = StringBuilder()
        ScheduleLog(home, taskId = "t1", tail = 10) { out.appendLine(it) }.run()
        out.toString() shouldContain "did stuff"
        (out.toString().contains("other")) shouldBe false
    }

    test("--schedule-dir on the list/log/pause/resume/remove commands points at a non-default directory") {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        val task = store.add(ScheduledTask(name = "custom-dir-task", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        RunLog(home.resolve("runs.jsonl")).append(RunRecord(task.id, 1L, 2L, RunOutcome.Succeeded, "custom dir run"))

        val listResult = ScheduleListCommand().test("--schedule-dir \"$home\"")
        listResult.output shouldContain "custom-dir-task"

        val logResult = ScheduleLogCommand().test("--schedule-dir \"$home\"")
        logResult.output shouldContain "custom dir run"

        SchedulePauseCommand().test("--schedule-dir \"$home\" ${task.id}")
        store.get(task.id)!!.enabled shouldBe false

        ScheduleResumeCommand().test("--schedule-dir \"$home\" ${task.id}")
        store.get(task.id)!!.enabled shouldBe true

        ScheduleRemoveCommand().test("--schedule-dir \"$home\" ${task.id}")
        store.list() shouldBe emptyList()
    }
})
