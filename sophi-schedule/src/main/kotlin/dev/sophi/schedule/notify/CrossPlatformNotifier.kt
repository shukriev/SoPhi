package dev.sophi.schedule.notify

import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask

class CrossPlatformNotifier(
    private val send: (title: String, body: String) -> Unit = { t, b -> NativeNotifications.send(t, b) }
) : Notifier {
    override fun notify(task: ScheduledTask, run: RunRecord) {
        val outcomeText = when (val o = run.outcome) {
            is RunOutcome.Succeeded -> "completed"
            is RunOutcome.GoalMet -> "goal met"
            is RunOutcome.GoalExhausted -> "goal not met (max iterations)"
            is RunOutcome.Failed -> "failed: ${o.error}"
        }
        send("Sophi: ${task.name}", "$outcomeText — ${run.summary.take(200)}")
    }
}
