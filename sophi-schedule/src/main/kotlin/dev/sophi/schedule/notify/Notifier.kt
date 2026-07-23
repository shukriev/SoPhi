package dev.sophi.schedule.notify

import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask

fun interface Notifier {
    fun notify(task: ScheduledTask, run: RunRecord)
}

object NoopNotifier : Notifier {
    override fun notify(task: ScheduledTask, run: RunRecord) = Unit
}
