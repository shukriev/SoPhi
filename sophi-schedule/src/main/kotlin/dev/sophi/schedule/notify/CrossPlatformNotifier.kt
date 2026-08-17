package dev.sophi.schedule.notify

import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask

class CrossPlatformNotifier(
    private val send: (title: String, body: String) -> Unit = { t, b -> NativeNotifications.send(t, b) }
) : Notifier {
    override fun notify(task: ScheduledTask, run: RunRecord) {
        val (title, body) = NotificationText.forTaskRun(task, run)
        send(title, body)
    }
}
