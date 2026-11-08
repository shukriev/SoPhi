package dev.sophi.schedule.engine

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.notify.Notifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

class ScheduleEngineFailureStreakTest : FunSpec({
    val notified = mutableListOf<RunRecord>()
    beforeTest { notified.clear() }

    fun setup(provider: LLMProvider): Triple<ScheduleEngine, TaskStore, RunLog> {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        val log = RunLog(home.resolve("runs.jsonl"))
        val engine = ScheduleEngine(
            store, log, provider, ToolRegistry(), FileSessionManager(createTempDirectory("streak")),
            Notifier { _, run -> notified.add(run) }, model = "m", contextWindowTokens = 100_000
        )
        return Triple(engine, store, log)
    }

    fun task(store: TaskStore) = store.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))

    fun failing(vararg messages: String): LLMProvider {
        val provider = mockk<LLMProvider>()
        var i = 0
        every { provider.stream(any()) } answers { throw RuntimeException(messages[i++ % messages.size]) }
        return provider
    }

    test("three runs failing the same way pause the task and tell the owner once") {
        val (engine, store, _) = setup(failing(
            "connection refused to 127.0.0.1:1234 after 3012ms",
            "connection refused to 127.0.0.1:1234 after 2987ms",
            "connection refused to 127.0.0.1:1234 after 3100ms"
        ))
        val t = task(store)

        runBlocking { repeat(3) { engine.runNow(t.id) } }

        val paused = store.get(t.id)!!
        paused.enabled shouldBe false
        paused.pausedReason!! shouldContain "connection refused"
        notified.filter { "resume" in it.summary } shouldHaveSize 1
        notified.last().summary shouldContain "resume"
    }

    test("three failures with different causes don't pause") {
        val (engine, store, _) = setup(failing("connection refused", "invalid api key", "model not found"))
        val t = task(store)

        runBlocking { repeat(3) { engine.runNow(t.id) } }

        store.get(t.id)!!.enabled shouldBe true
        store.get(t.id)!!.pausedReason shouldBe null
    }

    test("a success in between resets the streak") {
        val provider = mockk<LLMProvider>()
        var call = 0
        every { provider.stream(any()) } answers {
            if (call++ == 2) flowOf(StreamEvent.Content("ok")) else throw RuntimeException("boom")
        }
        val (engine, store, _) = setup(provider)
        val t = task(store)

        runBlocking { repeat(4) { engine.runNow(t.id) } } // fail, fail, ok, fail

        store.get(t.id)!!.enabled shouldBe true
    }

    test("an auto-paused task isn't run, and the skip says why in the run log") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("should not run"))
        val (engine, store, log) = setup(provider)
        val t = store.add(ScheduledTask(
            name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p",
            enabled = false, pausedReason = "invalid api key"
        ))

        runBlocking { engine.runNow(t.id) }

        val skip = log.forTask(t.id).single().outcome
        skip.shouldBeInstanceOf<RunOutcome.Skipped>()
        skip.reason shouldContain "invalid api key"
        verify(exactly = 0) { provider.stream(any()) }
    }

    test("skips don't count toward the streak") {
        val (engine, store, log) = setup(failing("boom"))
        val t = task(store)
        runBlocking { engine.runNow(t.id) }
        log.append(RunRecord(t.id, 0, 0, RunOutcome.Skipped("budget"), ""))
        runBlocking { engine.runNow(t.id) }
        store.get(t.id)!!.enabled shouldBe true
        runBlocking { engine.runNow(t.id) }
        store.get(t.id)!!.enabled shouldBe false
    }

    test("resume clears the engine's pause reason") {
        val store = TaskStore(tempdir().toPath().resolve("tasks.json"))
        val t = store.add(ScheduledTask(
            name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p",
            enabled = false, pausedReason = "invalid api key"
        ))

        store.setEnabled(t.id, true)

        store.get(t.id)!!.enabled shouldBe true
        store.get(t.id)!!.pausedReason shouldBe null
    }

    test("the cause key ignores numbers, ids and everything after the first line") {
        causeKey("timed out after 300s\nstack...") shouldBe causeKey("timed out after 120s")
        causeKey("task_3f2a9c1e failed") shouldBe causeKey("task_77b0d2aa failed")
        (causeKey("invalid api key") == causeKey("model not found")) shouldBe false
    }
})
