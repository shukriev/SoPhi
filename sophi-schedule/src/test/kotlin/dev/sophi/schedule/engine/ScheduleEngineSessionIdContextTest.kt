package dev.sophi.schedule.engine

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.notify.NoopNotifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

private const val TEST_CONTEXT_WINDOW = 100_000

class ScheduleEngineSessionIdContextTest : FunSpec({
    test("runTask launches the turn inside a SessionIdContext matching the run's own session id") {
        var observedSessionId: String? = null
        val probeTool = object : Tool {
            override val name = "probe"
            override val description = "captures the ambient SessionIdContext"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String {
                observedSessionId = kotlin.coroutines.coroutineContext[SessionIdContext]?.sessionId
                return "ok"
            }
        }
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "probe", "{}")))),
            flowOf(StreamEvent.Content("done"))
        )

        val home = tempdir().toPath()
        val taskStore = TaskStore(home.resolve("tasks.json"))
        val runLog = RunLog(home.resolve("runs.jsonl"))
        val engine = ScheduleEngine(
            taskStore = taskStore,
            runLog = runLog,
            provider = provider,
            fullRegistry = ToolRegistry().register(probeTool),
            sessionManager = FileSessionManager(home.resolve("sessions")),
            notifier = NoopNotifier,
            model = "test-model",
            contextWindowTokens = TEST_CONTEXT_WINDOW
        )
        val task = taskStore.add(ScheduledTask(
            name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "run the probe"
        ))

        val record = runBlocking { engine.runNow(task.id) }

        observedSessionId shouldBe record?.sessionId
    }
})
