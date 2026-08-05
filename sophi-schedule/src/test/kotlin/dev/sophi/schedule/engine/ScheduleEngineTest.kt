package dev.sophi.schedule.engine

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.ScheduledTask
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.io.path.createTempDirectory
import java.util.concurrent.atomic.AtomicInteger

private const val TEST_CONTEXT_WINDOW = 100_000

class ScheduleEngineTest : FunSpec({
    fun engine(
        provider: LLMProvider,
        notifier: Notifier = NoopNotifier,
        maxConcurrentTasks: Int = 4,
        taskTimeoutMs: Long = 300_000,
        registry: ToolRegistry = ToolRegistry()
    ): Triple<ScheduleEngine, TaskStore, RunLog> {
        val home = tempdir().toPath()
        val taskStore = TaskStore(home.resolve("tasks.json"))
        val runLog = RunLog(home.resolve("runs.jsonl"))
        val engine = ScheduleEngine(
            taskStore, runLog, provider, registry,
            FileSessionManager(createTempDirectory("schedule-engine-test")),
            notifier, model = "m", contextWindowTokens = TEST_CONTEXT_WINDOW,
            maxConcurrentTasks = maxConcurrentTasks, taskTimeoutMs = taskTimeoutMs
        )
        return Triple(engine, taskStore, runLog)
    }

    test("tickOnce runs a due Recurring task and records a Succeeded run") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("checked, nothing new"))
        val (engine, taskStore, runLog) = engine(provider)
        // Trigger.Once(atMs = 0L) makes nextRunAtMs deterministic (0), so tickOnce(nowMs = 1L) is due.
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "check"))

        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }
        runLog.forTask(task.id).single().outcome shouldBe RunOutcome.Succeeded
    }

    test("tickOnce skips a task whose nextRunAtMs is in the future") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("x"))
        val (engine, taskStore, runLog) = engine(provider)
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Interval(3600), mode = TaskMode.Recurring, prompt = "p"))
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = System.currentTimeMillis()) }
        runLog.forTask(task.id) shouldBe emptyList()
    }

    test("tickOnce lets a task run a tool that's in its toolGrants, unattended") {
        var executed = false
        val grantedTool = object : dev.sophi.core.tools.Tool {
            override val name = "danger"
            override val description = "risky"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String { executed = true; return "ran" }
        }
        val provider = mockk<LLMProvider>()
        var callCount = 0
        every { provider.stream(any()) } answers {
            callCount++
            if (callCount == 1)
                flowOf(StreamEvent.ToolCallsReady(listOf(
                    dev.sophi.ai.api.ToolCall("c1", "danger", "{}")
                )))
            else
                flowOf(StreamEvent.Content("done"))
        }
        val (engine, taskStore, runLog) = engine(provider, registry = ToolRegistry().register(grantedTool))
        val task = taskStore.add(ScheduledTask(
            name = "t", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "run it",
            toolGrants = setOf("danger")
        ))

        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }

        executed shouldBe true
        runLog.forTask(task.id).single().outcome shouldBe RunOutcome.Succeeded
    }

    test("tickOnce denies a tool that's not in the task's toolGrants, unattended") {
        var executed = false
        val ungranted = object : dev.sophi.core.tools.Tool {
            override val name = "danger"
            override val description = "risky"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String { executed = true; return "ran" }
        }
        val provider = mockk<LLMProvider>()
        var callCount = 0
        every { provider.stream(any()) } answers {
            callCount++
            if (callCount == 1)
                flowOf(StreamEvent.ToolCallsReady(listOf(
                    dev.sophi.ai.api.ToolCall("c1", "danger", "{}")
                )))
            else
                flowOf(StreamEvent.Content("done"))
        }
        val (engine, taskStore, _) = engine(provider, registry = ToolRegistry().register(ungranted))
        taskStore.add(ScheduledTask(
            name = "t", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "run it",
            toolGrants = emptySet()
        ))

        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }

        executed shouldBe false
    }

    test("tickOnce runs multiple due tasks concurrently, not sequentially") {
        val provider = mockk<LLMProvider>()
        val inFlight = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        every { provider.stream(any()) } returns flow {
            val now = inFlight.incrementAndGet()
            maxObservedConcurrency.updateAndGet { maxOf(it, now) }
            kotlinx.coroutines.delay(50)
            inFlight.decrementAndGet()
            emit(StreamEvent.Content("done"))
        }
        val (engine, taskStore, _) = engine(provider, maxConcurrentTasks = 4)
        taskStore.add(ScheduledTask(name = "a", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "p"))
        taskStore.add(ScheduledTask(name = "b", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "p"))
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }
        (maxObservedConcurrency.get() >= 2) shouldBe true
    }

    test("one task's failure does not abort the tick or other due tasks") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } throws RuntimeException("LLM unreachable")
        val (engine, taskStore, runLog) = engine(provider)
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "p"))
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }
        val record = runLog.forTask(task.id).single()
        (record.outcome is RunOutcome.Failed) shouldBe true
    }

    test("a Goal-mode task runs via PlanRunner and records GoalMet") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("did the thing"))
        coEvery { provider.complete(any()) } returns LLMResponse.Text("YES", TokenUsage(1, 1))
        val (engine, taskStore, runLog) = engine(provider)
        val task = taskStore.add(ScheduledTask(
            name = "goal-task", trigger = Trigger.Once(atMs = 0L),
            mode = TaskMode.Goal(StopCondition.LlmJudged, maxIterations = 3), prompt = "do it"))
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }
        runLog.forTask(task.id).single().outcome shouldBe RunOutcome.GoalMet
    }

    test("runNow executes a task immediately regardless of nextRunAtMs and returns its RunRecord") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("manual run"))
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
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("x"))
        val notified = mutableListOf<String>()
        val (engine, taskStore, _) = engine(provider, notifier = Notifier { task, _ -> notified.add(task.id) })
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        kotlinx.coroutines.runBlocking { engine.runNow(task.id) }
        notified shouldBe listOf(task.id)
    }

    test("a run exceeding taskTimeoutMs is recorded as Failed with a clear timeout message, not hung forever") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flow {
            kotlinx.coroutines.delay(500)
            emit(StreamEvent.Content("too late"))
        }
        val (engine, taskStore, _) = engine(provider, taskTimeoutMs = 50)
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        val record = kotlinx.coroutines.runBlocking { engine.runNow(task.id) }
        (record?.outcome is RunOutcome.Failed) shouldBe true
        (record?.outcome as RunOutcome.Failed).error shouldContain "timed out"
    }

    test("maxTokens is configurable and threaded into every task's AgentConfig") {
        val provider = mockk<LLMProvider>()
        var capturedMaxTokens: Int? = null
        every { provider.stream(any()) } answers {
            capturedMaxTokens = firstArg<CompletionRequest>().maxTokens
            flowOf(StreamEvent.Content("x"))
        }
        val home = tempdir().toPath()
        val taskStore = TaskStore(home.resolve("tasks.json"))
        val runLog = RunLog(home.resolve("runs.jsonl"))
        val engine = ScheduleEngine(
            taskStore, runLog, provider, ToolRegistry(),
            FileSessionManager(createTempDirectory("schedule-engine-maxtokens-test")),
            NoopNotifier, model = "m", contextWindowTokens = TEST_CONTEXT_WINDOW, maxTokens = 8192
        )
        val task = taskStore.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))
        kotlinx.coroutines.runBlocking { engine.runNow(task.id) }
        capturedMaxTokens shouldBe 8192
    }

    test("one task timing out does not abort concurrently-running tasks in the same tick") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } answers {
            val req = firstArg<CompletionRequest>()
            if (req.messages.first().content == "slow") {
                flow {
                    kotlinx.coroutines.delay(500)
                    emit(StreamEvent.Content("too late"))
                }
            } else {
                flowOf(StreamEvent.Content("fast"))
            }
        }
        val (engine, taskStore, runLog) = engine(provider, taskTimeoutMs = 50)
        val slow = taskStore.add(ScheduledTask(name = "slow", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "slow"))
        val fast = taskStore.add(ScheduledTask(name = "fast", trigger = Trigger.Once(atMs = 0L), mode = TaskMode.Recurring, prompt = "fast"))
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 1L) }
        (runLog.forTask(slow.id).single().outcome is RunOutcome.Failed) shouldBe true
        runLog.forTask(fast.id).single().outcome shouldBe RunOutcome.Succeeded
    }

    test("a due Cron-triggered task fires via tickOnce and records a Succeeded run") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("checked cron task"))
        val (engine, taskStore, runLog) = engine(provider)
        val task = taskStore.add(ScheduledTask(
            name = "t", trigger = Trigger.Cron("0 9 * * *"), mode = TaskMode.Recurring, prompt = "check"))
        // add() computes a future nextRunAtMs for a Cron trigger; force it due, the same
        // way the Trigger.Once(atMs = 0L) tests elsewhere in this file already do.
        taskStore.update(task.id) { it.copy(nextRunAtMs = 1L) }
        kotlinx.coroutines.runBlocking { engine.tickOnce(nowMs = 2L) }
        runLog.forTask(task.id).single().outcome shouldBe RunOutcome.Succeeded
    }
})
