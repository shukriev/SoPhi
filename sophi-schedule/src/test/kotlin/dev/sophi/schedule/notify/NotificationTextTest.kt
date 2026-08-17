package dev.sophi.schedule.notify

import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NotificationTextTest : FunSpec({
    val task = ScheduledTask(id = "t1", name = "Twitter monitor", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p")

    test("forTaskRun titles with the task name and bodies with outcome + summary") {
        val (title, body) = NotificationText.forTaskRun(task, RunRecord("t1", 0L, 1L, RunOutcome.Succeeded, "nothing new"))
        title shouldBe "Sophi: Twitter monitor"
        body shouldBe "completed — nothing new"
    }

    test("forTaskRun reports a failed outcome's error message") {
        val (_, body) = NotificationText.forTaskRun(task, RunRecord("t1", 0L, 1L, RunOutcome.Failed("boom"), ""))
        body shouldBe "failed: boom — "
    }

    test("forTaskRun distinguishes goal-met and goal-exhausted outcomes") {
        NotificationText.forTaskRun(task, RunRecord("t1", 0L, 1L, RunOutcome.GoalMet, "done")).second shouldBe "goal met — done"
        NotificationText.forTaskRun(task, RunRecord("t1", 0L, 1L, RunOutcome.GoalExhausted, "gave up")).second shouldBe "goal not met (max iterations) — gave up"
    }

    test("forTaskRun truncates the summary to 200 characters") {
        val longSummary = "x".repeat(300)
        val (_, body) = NotificationText.forTaskRun(task, RunRecord("t1", 0L, 1L, RunOutcome.Succeeded, longSummary))
        body shouldBe "completed — ${"x".repeat(200)}"
    }
})
