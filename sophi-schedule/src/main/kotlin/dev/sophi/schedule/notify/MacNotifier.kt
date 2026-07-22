package dev.sophi.schedule.notify

import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask

class MacNotifier(
    private val runCommand: (List<String>) -> Int = { cmd -> ProcessBuilder(cmd).start().waitFor() }
) : Notifier {
    override fun notify(task: ScheduledTask, run: RunRecord) {
        val outcomeText = when (val o = run.outcome) {
            is RunOutcome.Succeeded -> "completed"
            is RunOutcome.GoalMet -> "goal met"
            is RunOutcome.GoalExhausted -> "goal not met (max iterations)"
            is RunOutcome.Failed -> "failed: ${o.error}"
        }
        val script = "display notification ${quote(run.summary.take(200))} " +
            "with title ${quote("Sophi: ${task.name}")} subtitle ${quote(outcomeText)}"
        runCatching { runCommand(listOf("osascript", "-e", script)) }
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
