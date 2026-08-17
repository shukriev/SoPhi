package dev.sophi.cli

import dev.sophi.hub.HubEvent
import dev.sophi.hub.HubServer
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.notify.Notifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

class HubFallbackNotifierTest : FunSpec({
    val task = ScheduledTask(id = "t1", name = "Twitter monitor", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p")
    val run = RunRecord("t1", 0L, 1L, RunOutcome.Succeeded, "nothing new")

    test("hub reachable: publishes a ScheduleNotification and never calls the OS fallback") {
        val port = freePort()
        val server = HubServer(port)
        server.start()
        var osCalled = false
        try {
            runBlocking {
                withTimeout(5000) {
                    val received = async(Dispatchers.Default) { server.events.first { it is HubEvent.ScheduleNotification } }
                    delay(50) // let the collector attach before notify() publishes

                    val notifier = HubFallbackNotifier(hubPort = port, osNotifier = Notifier { _, _ -> osCalled = true })
                    notifier.notify(task, run)

                    val event = received.await() as HubEvent.ScheduleNotification
                    event.sessionId shouldBe "t1"
                    event.title shouldBe "Sophi: Twitter monitor"
                    event.body shouldBe "completed — nothing new"
                }
            }
        } finally {
            server.stop()
        }
        osCalled shouldBe false
    }

    test("hub unreachable: falls back to the OS notifier with the same task/run it received") {
        val port = freePort() // nothing listening
        var capturedTask: ScheduledTask? = null
        var capturedRun: RunRecord? = null
        val notifier = HubFallbackNotifier(hubPort = port, osNotifier = Notifier { t, r -> capturedTask = t; capturedRun = r })

        notifier.notify(task, run)

        capturedTask shouldBe task
        capturedRun shouldBe run
    }
})
