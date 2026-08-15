package dev.sophi.cli

import dev.sophi.hub.HubClient
import dev.sophi.hub.HubEvent
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.notify.CrossPlatformNotifier
import dev.sophi.schedule.notify.NotificationText
import dev.sophi.schedule.notify.Notifier
import kotlinx.coroutines.runBlocking

/**
 * Delivers schedule-task notifications through companion's local hub when it's reachable,
 * falling back to an OS-level notification otherwise — mirrors HubClient's own "must behave
 * identically with or without a companion running" contract.
 */
class HubFallbackNotifier(
    private val hubPort: Int = 8765,
    private val osNotifier: Notifier = CrossPlatformNotifier()
) : Notifier {
    override fun notify(task: ScheduledTask, run: RunRecord) = runBlocking {
        val (title, body) = NotificationText.forTaskRun(task, run)
        val client = HubClient(hubPort, sessionId = task.id)
        try {
            if (client.connect(this)) {
                client.publish(HubEvent.ScheduleNotification(task.id, title, body))
            } else {
                osNotifier.notify(task, run)
            }
        } finally {
            client.close()
        }
    }
}
