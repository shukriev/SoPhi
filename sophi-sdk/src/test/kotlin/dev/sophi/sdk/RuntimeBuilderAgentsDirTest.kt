package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.notify.NoopNotifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlin.io.path.writeText

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderAgentsDirTest : FunSpec({

    test("agentsDir() loads definitions and threads them into scheduleEngine(), so a matching subagentType runs scoped") {
        val agentsDir = tempdir().toPath()
        agentsDir.resolve("explorer.md").writeText("---\nname: explorer\ndescription: reads things\n---\nYou explore.")
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("scoped run ok"))
        val runtime = RuntimeBuilder().apply {
            this.provider = provider
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).agentsDir(agentsDir).build()

        val dir = tempdir().toPath()
        val taskStore = TaskStore(dir.resolve("tasks.json"))
        val runLog = RunLog(dir.resolve("runs.jsonl"))
        val engine = runtime.scheduleEngine(taskStore, runLog, NoopNotifier)
        val task = taskStore.add(ScheduledTask(
            name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p",
            subagentType = "explorer"
        ))

        val record = kotlinx.coroutines.runBlocking { engine.runNow(task.id) }

        record?.outcome shouldBe RunOutcome.Succeeded
    }

    test("without agentsDir(), a task whose subagentType is set fails closed instead of running unscoped") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("should never run"))
        val runtime = RuntimeBuilder().apply {
            this.provider = provider
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).build()

        val dir = tempdir().toPath()
        val taskStore = TaskStore(dir.resolve("tasks.json"))
        val runLog = RunLog(dir.resolve("runs.jsonl"))
        val engine = runtime.scheduleEngine(taskStore, runLog, NoopNotifier)
        val task = taskStore.add(ScheduledTask(
            name = "orphaned", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p",
            subagentType = "ghost"
        ))

        val record = kotlinx.coroutines.runBlocking { engine.runNow(task.id) }

        (record?.outcome is RunOutcome.Failed) shouldBe true
        (record?.outcome as RunOutcome.Failed).error shouldContain "ghost"
    }

    test("agentsDir() invokes onWarning and yields no definitions when a definition file is malformed") {
        val agentsDir = tempdir().toPath()
        agentsDir.resolve("broken.md").writeText("not a valid agent definition")
        val warnings = mutableListOf<String>()
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("x"))
        val runtime = RuntimeBuilder().apply {
            this.provider = provider
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW)
            .agentsDir(agentsDir, onWarning = { warnings.add(it) })
            .build()

        val dir = tempdir().toPath()
        val taskStore = TaskStore(dir.resolve("tasks.json"))
        val runLog = RunLog(dir.resolve("runs.jsonl"))
        val engine = runtime.scheduleEngine(taskStore, runLog, NoopNotifier)
        val task = taskStore.add(ScheduledTask(
            name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p",
            subagentType = "explorer"
        ))

        val record = kotlinx.coroutines.runBlocking { engine.runNow(task.id) }

        warnings shouldHaveSize 1
        (record?.outcome is RunOutcome.Failed) shouldBe true
    }
})
