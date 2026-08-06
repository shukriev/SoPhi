package dev.sophi.schedule.notify

import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CrossPlatformNotifierTest : FunSpec({
    val task = ScheduledTask(id = "t1", name = "Twitter monitor", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p")

    test("notify sends a title naming the task and a body naming the outcome and summary") {
        var capturedTitle: String? = null
        var capturedBody: String? = null
        val notifier = CrossPlatformNotifier(send = { t, b -> capturedTitle = t; capturedBody = b })

        notifier.notify(task, RunRecord("t1", 0L, 1L, RunOutcome.Succeeded, "nothing new"))

        capturedTitle shouldBe "Sophi: Twitter monitor"
        capturedBody shouldContain "completed"
        capturedBody shouldContain "nothing new"
    }

    test("notify reports a failed outcome's error message") {
        var capturedBody: String? = null
        val notifier = CrossPlatformNotifier(send = { _, b -> capturedBody = b })

        notifier.notify(task, RunRecord("t1", 0L, 1L, RunOutcome.Failed("boom"), ""))

        capturedBody shouldContain "failed: boom"
    }

    test("notify reports goal-met and goal-exhausted outcomes distinctly") {
        var capturedBody: String? = null
        val notifier = CrossPlatformNotifier(send = { _, b -> capturedBody = b })

        notifier.notify(task, RunRecord("t1", 0L, 1L, RunOutcome.GoalMet, "done"))
        capturedBody shouldContain "goal met"

        notifier.notify(task, RunRecord("t1", 0L, 1L, RunOutcome.GoalExhausted, "gave up"))
        capturedBody shouldContain "goal not met"
    }
})
