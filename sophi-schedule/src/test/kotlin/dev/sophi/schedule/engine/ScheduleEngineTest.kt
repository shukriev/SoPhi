package dev.sophi.schedule.engine

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.StopCondition
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.notify.NoopNotifier
import dev.sophi.schedule.notify.Notifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.io.path.createTempDirectory
import java.util.concurrent.atomic.AtomicInteger

class ScheduleEngineTest : FunSpec({
    fun engine(
        provider: LLMProvider,
        notifier: Notifier = NoopNotifier,
        maxConcurrentTasks: Int = 4,
        taskTimeoutMs: Long = 300_000
    ): Triple<ScheduleEngine, TaskStore, RunLog> {
        val home = tempdir().toPath()
        val taskStore = TaskStore(home.resolve("tasks.json"))
        val runLog = RunLog(home.resolve("runs.jsonl"))
        val engine = ScheduleEngine(
            taskStore, runLog, provider, ToolRegistry(),
            FileSessionManager(createTempDirectory("schedule-engine-test")),
            notifier, model = "m", maxConcurrentTasks = maxConcurrentTasks, taskTimeoutMs = taskTimeoutMs
        )
        return Triple(engine, taskStore, runLog)
    }

    test("tickOnce runs a due Recurring task and records a Succeeded run") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("checked, nothing new", TokenUsage(1, 1))
        val (engine, taskStore, runLog) = engine(provider)
        // Trigger.Once(atMs = 0L) makes nextRunAtMs deterministic (0), so tickOnce(nowMs = 1L) is due.
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "check"))

        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }
        runLog.forTask(task.id).single().outcome shouldBe RunOutcome.Succeeded
    }

    test("tickOnce skips a task whose nextRunAtMs is in the future") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("x", TokenUsage(1, 1))
        val (engine, taskStore, runLog) = engine(provider)
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Interval(3600), mode = TaskMode.Recurring, prompt = "p"))
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = System.currentTimeMillis()) }
        runLog.forTask(task.id) shouldBe emptyList()
    }

    test("tickOnce runs multiple due tasks concurrently, not sequentially") {
        val provider = mockk<LLMProvider>()
        val inFlight = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        coEvery { provider.complete(any()) } coAnswers {
            val now = inFlight.incrementAndGet()
            maxObservedConcurrency.updateAndGet { maxOf(it, now) }
            kotlinx.coroutines.delay(50)
            inFlight.decrementAndGet()
            LLMResponse.Text("done", TokenUsage(1, 1))
        }
        val (engine, taskStore, _) = engine(provider, maxConcurrentTasks = 4)
        taskStore.add(ScheduledTask(name = "a", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "p"))
        taskStore.add(ScheduledTask(name = "b", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "p"))
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }
        (maxObservedConcurrency.get() >= 2) shouldBe true
    }

    test("one task's failure does not abort the tick or other due tasks") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } throws RuntimeException("LLM unreachable")
        val (engine, taskStore, runLog) = engine(provider)
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "p"))
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }
        val record = runLog.forTask(task.id).single()
        (record.outcome is RunOutcome.Failed) shouldBe true
    }

    test("a Goal-mode task runs via GoalRunner and records GoalMet") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } answers {
            val req = firstArg<CompletionRequest>()
            if (req.maxTokens == 8) LLMResponse.Text("YES", TokenUsage(1, 1))
            else LLMResponse.Text("did the thing", TokenUsage(1, 1))
        }
        val (engine, taskStore, runLog) = engine(provider)
        val task = taskStore.add(ScheduledTask(
            name = "goal-task", trigger = Trigger.Once(atMs = 0L),
            mode = TaskMode.Goal(StopCondition.LlmJudged, maxIterations = 3), prompt = "do it"))
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }
        runLog.forTask(task.id).single().outcome shouldBe RunOutcome.GoalMet
    }

    test("runNow executes a task immediately regardless of nextRunAtMs and returns its RunRecord") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("manual run", TokenUsage(1, 1))
        val (engine, taskStore, _) = engine(provider)
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val record = kotlinx.coroutines.runBlocking { engine.runNow(task.id) }
        record?.outcome shouldBe RunOutcome.Succeeded
    }

    test("runNow returns null for an unknown task id") {
        val provider = mockk<LLMProvider>()
        val (engine, _, _) = engine(provider)
        val record = kotlinx.coroutines.runBlocking { engine.runNow("no-such-id") }
        record shouldBe null
    }

    test("notifier is called once per completed run") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("x", TokenUsage(1, 1))
        val notified = mutableListOf<String>()
        val (engine, taskStore, _) = engine(provider, notifier = Notifier { task, _ -> notified.add(task.id) })
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        kotlinx.coroutines.runBlocking { engine.runNow(task.id) }
        notified shouldBe listOf(task.id)
    }

    test("a run exceeding taskTimeoutMs is recorded as Failed with a clear timeout message, not hung forever") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } coAnswers {
            kotlinx.coroutines.delay(500)
            LLMResponse.Text("too late", TokenUsage(1, 1))
        }
        val (engine, taskStore, _) = engine(provider, taskTimeoutMs = 50)
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val record = kotlinx.coroutines.runBlocking { engine.runNow(task.id) }
        (record?.outcome is RunOutcome.Failed) shouldBe true
        (record?.outcome as RunOutcome.Failed).error shouldContain "timed out"
    }

    test("one task timing out does not abort concurrently-running tasks in the same tick") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } coAnswers {
            val req = firstArg<CompletionRequest>()
            if (req.messages.first().content == "slow") {
                kotlinx.coroutines.delay(500)
                LLMResponse.Text("too late", TokenUsage(1, 1))
            } else {
                LLMResponse.Text("fast", TokenUsage(1, 1))
            }
        }
        val (engine, taskStore, runLog) = engine(provider, taskTimeoutMs = 50)
        val slow = taskStore.add(ScheduledTask(name = "slow", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "slow"))
        val fast = taskStore.add(ScheduledTask(name = "fast", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "fast"))
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }
        (runLog.forTask(slow.id).single().outcome is RunOutcome.Failed) shouldBe true
        runLog.forTask(fast.id).single().outcome shouldBe RunOutcome.Succeeded
    }
})
