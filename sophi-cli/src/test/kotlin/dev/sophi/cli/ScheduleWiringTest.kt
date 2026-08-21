package dev.sophi.cli

import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory

class ScheduleWiringTest : FunSpec({

    test("buildScheduleEngine fails a task closed when its subagentType matches no definition loaded from agentsDir") {
        val agentsDir = createTempDirectory("agents-test-empty")
        val scheduleDir = createTempDirectory("schedule-test")
        val engine = buildScheduleEngine(
            model = "m", providerType = "claude", apiKeyOption = "test-key", baseUrl = null,
            scheduleDir = scheduleDir, sessionsDir = createTempDirectory("sessions-test"),
            agentsDir = agentsDir, braveApiKeyOption = null, contextWindowTokens = 100_000
        )
        val taskStore = TaskStore(scheduleDir.resolve("tasks.json"))
        val task = taskStore.add(ScheduledTask(
            name = "orphaned", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p",
            subagentType = "ghost"
        ))

        // This throws (and is caught into RunOutcome.Failed) before any provider call is made —
        // ScheduleEngine.runTask resolves subagentType before constructing the AgentLoop — so
        // this test needs no real or mocked LLM endpoint. It proves buildScheduleEngine's new
        // Sophi.runtime{}-based wiring threads agentsDir's (empty, here) definitions into the
        // resulting ScheduleEngine correctly, exercising the exact production code path.
        val record = kotlinx.coroutines.runBlocking { engine.runNow(task.id) }

        (record?.outcome is RunOutcome.Failed) shouldBe true
        (record?.outcome as RunOutcome.Failed).error shouldContain "ghost"
    }
})
