package dev.sophi.companion

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.schedule.notify.NoopNotifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import dev.sophi.sdk.Sophi
import dev.sophi.sdk.SophiRuntime
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

private class CheckInFakeProvider(private val fail: Boolean = false) : LLMProvider {
    override val name = "fake"
    override suspend fun complete(request: CompletionRequest): LLMResponse =
        LLMResponse.Text(content = "logged", usage = TokenUsage(0, 0))
    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        if (fail) throw RuntimeException("boom")
        emit(StreamEvent.Content("logged"))
    }
}

private fun newRuntime(dir: Path): CompanionRuntime {
    val sophiRuntime = Sophi.runtime {
        provider = CheckInFakeProvider()
        model = "fake-model"
        contextWindowTokens(200_000)
        sessionsDir = dir.resolve("sessions")
    }
    return CompanionRuntime(
        sophiRuntime = sophiRuntime,
        sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
        mcpConfigPath = dir.resolve("mcp.json"),
        taskStore = TaskStore(dir.resolve("tasks.json")),
        runLog = RunLog(dir.resolve("runs.jsonl")),
        notifier = NoopNotifier,
        notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
    )
}

private fun fakeCheckInRuntime(dir: Path, fail: Boolean = false): SophiRuntime = Sophi.runtime {
    provider = CheckInFakeProvider(fail = fail)
    model = "fake-model"
    contextWindowTokens(200_000)
    sessionsDir = dir.resolve("checkin-sessions")
}

private suspend fun waitUntil(timeoutMs: Long, poll: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (poll()) return
        delay(10)
    }
    error("waitUntil timed out after ${timeoutMs}ms")
}

class CompanionRuntimeCheckInTest : FunSpec({
    test("a cancelled dialog (null promptText) runs no turn and adds no notification") {
        val dir = createTempDirectory("checkin-test")
        val runtime = newRuntime(dir)
        val checkIn = CheckIn("work-log", "What have you worked on?", "0 * * * *")
        val fireAt = dev.sophi.schedule.model.CronSchedules.nextFireTimeAfter(checkIn.cronExpression, System.currentTimeMillis() - 1)!!

        runtime.startCheckInScheduling(
            checkIns = listOf(checkIn), jiraBaseUrl = null, checkInRuntime = fakeCheckInRuntime(dir),
            intervalMs = 10, nowMs = { fireAt }, promptText = { _, _ -> null }
        )

        runBlocking { delay(200) }
        runtime.notificationCenter.records.value shouldBe emptyList()
        runtime.close()
    }

    test("a captured reply runs one turn and adds a success notification") {
        val dir = createTempDirectory("checkin-test")
        val runtime = newRuntime(dir)
        val checkIn = CheckIn("work-log", "What have you worked on?", "0 * * * *")
        val fireAt = dev.sophi.schedule.model.CronSchedules.nextFireTimeAfter(checkIn.cronExpression, System.currentTimeMillis() - 1)!!

        runtime.startCheckInScheduling(
            checkIns = listOf(checkIn), jiraBaseUrl = null, checkInRuntime = fakeCheckInRuntime(dir),
            intervalMs = 10, nowMs = { fireAt }, promptText = { _, _ -> "fixed the flaky test" }
        )

        runBlocking { waitUntil(2000) { runtime.notificationCenter.records.value.isNotEmpty() } }
        val record = runtime.notificationCenter.records.value.single()
        record.kind shouldBe NotificationKind.CheckIn
        record.title shouldBe "Logged: work-log"
        record.body shouldBe "fixed the flaky test"
        runtime.close()
    }

    test("only one notification appears even though the tick loop runs many times at a constant nowMs") {
        val dir = createTempDirectory("checkin-test")
        val runtime = newRuntime(dir)
        val checkIn = CheckIn("work-log", "What have you worked on?", "0 * * * *")
        val fireAt = dev.sophi.schedule.model.CronSchedules.nextFireTimeAfter(checkIn.cronExpression, System.currentTimeMillis() - 1)!!

        runtime.startCheckInScheduling(
            checkIns = listOf(checkIn), jiraBaseUrl = null, checkInRuntime = fakeCheckInRuntime(dir),
            intervalMs = 5, nowMs = { fireAt }, promptText = { _, _ -> "fixed the flaky test" }
        )

        runBlocking { waitUntil(2000) { runtime.notificationCenter.records.value.isNotEmpty() } }
        runBlocking { delay(200) } // several more ticks at the same constant fireAt
        runtime.notificationCenter.records.value.size shouldBe 1
        runtime.close()
    }

    test("a turn that throws adds a failure notification instead") {
        val dir = createTempDirectory("checkin-test")
        val runtime = newRuntime(dir)
        val checkIn = CheckIn("work-log", "What have you worked on?", "0 * * * *")
        val fireAt = dev.sophi.schedule.model.CronSchedules.nextFireTimeAfter(checkIn.cronExpression, System.currentTimeMillis() - 1)!!

        runtime.startCheckInScheduling(
            checkIns = listOf(checkIn), jiraBaseUrl = null, checkInRuntime = fakeCheckInRuntime(dir, fail = true),
            intervalMs = 10, nowMs = { fireAt }, promptText = { _, _ -> "fixed the flaky test" }
        )

        runBlocking { waitUntil(2000) { runtime.notificationCenter.records.value.isNotEmpty() } }
        val record = runtime.notificationCenter.records.value.single()
        record.kind shouldBe NotificationKind.CheckIn
        record.title shouldBe "Check-in failed: work-log"
        runtime.close()
    }

    test("an empty checkIns list starts no polling job") {
        val dir = createTempDirectory("checkin-test")
        val runtime = newRuntime(dir)

        runtime.startCheckInScheduling(
            checkIns = emptyList(), jiraBaseUrl = null, checkInRuntime = fakeCheckInRuntime(dir),
            promptText = { _, _ -> "should never be called" }
        )

        runBlocking { delay(200) }
        runtime.notificationCenter.records.value shouldBe emptyList()
        runtime.close()
    }
})
