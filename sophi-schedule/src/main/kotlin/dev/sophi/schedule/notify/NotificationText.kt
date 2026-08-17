package dev.sophi.schedule.notify

import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask

object NotificationText {
    fun forTaskRun(task: ScheduledTask, run: RunRecord): Pair<String, String> {
        val outcomeText = when (val o = run.outcome) {
            is RunOutcome.Succeeded -> "completed"
            is RunOutcome.GoalMet -> "goal met"
            is RunOutcome.GoalExhausted -> "goal not met (max iterations)"
            is RunOutcome.Failed -> "failed: ${o.error}"
        }
        return "Sophi: ${task.name}" to "$outcomeText — ${run.summary.take(200)}"
    }
}
