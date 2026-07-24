package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.session.SessionMeta
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.model.Trigger
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class SlashCommandsTest : FunSpec({
    val sessionManager = mockk<SessionManager>(relaxed = true)
    val config = AgentConfig(model = "test-model")
    val output = mutableListOf<String>()

    beforeTest {
        clearMocks(sessionManager)
        output.clear()
    }

    fun makeSession(pairs: Int = 0): AgentSession {
        val s = AgentSession(id = "s1")
        repeat(pairs) { i ->
            s.append(EntryRole.USER, "msg $i")
            s.append(EntryRole.ASSISTANT, "resp $i")
        }
        return s
    }

    test("/list outputs one line per session") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        every { sessionManager.list() } returns listOf(
            SessionMeta("sess-1", 4, 1000L),
            SessionMeta("sess-2", 8, 2000L)
        )
        handler.handle("/list", AgentSession(id = "x"))
        output shouldBe listOf("sess-1  4 entries", "sess-2  8 entries")
    }

    test("/list shows empty message when no sessions") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        every { sessionManager.list() } returns emptyList()
        handler.handle("/list", AgentSession(id = "x"))
        output shouldBe listOf("No saved sessions.")
    }

    test("/branch shows numbered entries with role and content preview") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        val session = makeSession(pairs = 1)
        handler.handle("/branch", session)
        output shouldHaveSize 2
        output[0].contains("USER") shouldBe true
        output[1].contains("ASSISTANT") shouldBe true
    }

    test("/branch shows empty message for empty session") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/branch", AgentSession(id = "s1"))
        output shouldBe listOf("(empty)")
    }

    test("/checkout switches session tip to the given entry id") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        val session = makeSession(pairs = 2)
        val targetId = session.branch().first().id
        handler.handle("/checkout $targetId", session)
        session.tip?.id shouldBe targetId
        output shouldHaveSize 1
        output[0] shouldBe "Checked out entry $targetId"
    }

    test("/checkout with no argument shows usage message") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/checkout", AgentSession(id = "s1"))
        output shouldBe listOf("Usage: /checkout <entry-id>")
    }

    test("/checkout with invalid id outputs error without throwing") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/checkout nonexistent-id", AgentSession(id = "s1"))
        output shouldHaveSize 1
        output[0].startsWith("Error:") shouldBe true
    }

    test("/compact compacts session, saves it, and reports entry count") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(0, 0))
        val compactor = ContextCompactor(provider)
        val handler = SlashHandler(sessionManager, compactor, config) { output.add(it) }
        val session = makeSession(pairs = 4)  // 8 entries — more than keepRecentCount=4
        handler.handle("/compact", session)
        verify { sessionManager.save(any()) }
        output shouldHaveSize 1
        output[0].startsWith("Compacted to") shouldBe true
    }

    test("/compact without compactor configured shows error") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/compact", AgentSession(id = "s1"))
        output shouldBe listOf("No compactor configured.")
    }

    test("unknown command shows available commands") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/banana", AgentSession(id = "s1"))
        output shouldHaveSize 1
        output[0].contains("Unknown command") shouldBe true
        output[0].contains("/list") shouldBe true
    }

    test("/schedule (no args) lists tasks with their enabled/paused state") {
        val scheduleDir = tempdir().toPath()
        val taskStore = TaskStore(scheduleDir.resolve("tasks.json"))
        taskStore.add(ScheduledTask(name = "t1", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        val handler = SlashHandler(sessionManager, null, config, scheduleDir = scheduleDir) { output.add(it) }
        handler.handle("/schedule", AgentSession(id = "s1"))
        output shouldHaveSize 1
        output[0] shouldContain "t1"
        output[0] shouldContain "enabled"
    }

    test("/schedule list shows 'No scheduled tasks.' when empty") {
        val handler = SlashHandler(sessionManager, null, config, scheduleDir = tempdir().toPath()) { output.add(it) }
        handler.handle("/schedule list", AgentSession(id = "s1"))
        output shouldBe listOf("No scheduled tasks.")
    }

    test("/schedule pause pauses a task by id") {
        val scheduleDir = tempdir().toPath()
        val taskStore = TaskStore(scheduleDir.resolve("tasks.json"))
        val task = taskStore.add(ScheduledTask(name = "t1", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        val handler = SlashHandler(sessionManager, null, config, scheduleDir = scheduleDir) { output.add(it) }
        handler.handle("/schedule pause ${task.id}", AgentSession(id = "s1"))
        output shouldBe listOf("Paused ${task.id}")
        taskStore.get(task.id)?.enabled shouldBe false
    }

    test("/schedule pause with no id shows usage") {
        val handler = SlashHandler(sessionManager, null, config, scheduleDir = tempdir().toPath()) { output.add(it) }
        handler.handle("/schedule pause", AgentSession(id = "s1"))
        output shouldBe listOf("Usage: /schedule pause <task-id>")
    }

    test("/schedule resume resumes a paused task by id") {
        val scheduleDir = tempdir().toPath()
        val taskStore = TaskStore(scheduleDir.resolve("tasks.json"))
        val task = taskStore.add(ScheduledTask(
            name = "t1", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p", enabled = false
        ))
        val handler = SlashHandler(sessionManager, null, config, scheduleDir = scheduleDir) { output.add(it) }
        handler.handle("/schedule resume ${task.id}", AgentSession(id = "s1"))
        output shouldBe listOf("Resumed ${task.id}")
        taskStore.get(task.id)?.enabled shouldBe true
    }

    test("/schedule remove removes a task by id") {
        val scheduleDir = tempdir().toPath()
        val taskStore = TaskStore(scheduleDir.resolve("tasks.json"))
        val task = taskStore.add(ScheduledTask(name = "t1", trigger = Trigger.Interval(60), mode = TaskMode.Recurring, prompt = "p"))
        val handler = SlashHandler(sessionManager, null, config, scheduleDir = scheduleDir) { output.add(it) }
        handler.handle("/schedule remove ${task.id}", AgentSession(id = "s1"))
        output shouldBe listOf("Removed ${task.id}")
        taskStore.get(task.id) shouldBe null
    }

    test("/schedule log shows 'No run history.' when empty") {
        val handler = SlashHandler(sessionManager, null, config, scheduleDir = tempdir().toPath()) { output.add(it) }
        handler.handle("/schedule log", AgentSession(id = "s1"))
        output shouldBe listOf("No run history.")
    }

    test("/schedule with an unknown subcommand reports it") {
        val handler = SlashHandler(sessionManager, null, config, scheduleDir = tempdir().toPath()) { output.add(it) }
        handler.handle("/schedule banana", AgentSession(id = "s1"))
        output shouldHaveSize 1
        output[0] shouldContain "Unknown /schedule subcommand"
    }

    test("/feedback shows disabled message when learning is not enabled") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/feedback list", AgentSession(id = "s1"))
        output shouldBe listOf("Learning is not enabled.")
    }

    test("/feedback list shows 'No feedback records.' when empty") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"))
        val handler = SlashHandler(sessionManager, null, config, learning, learningHome = home) { output.add(it) }
        handler.handle("/feedback list /proj", AgentSession(id = "s1"))
        output shouldBe listOf("No feedback records.")
    }

    test("/feedback list shows a recorded feedback entry from the same learning home") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"))
        learning.recordExplicitFeedback("s1", 0, "positive", "great answer")
        val handler = SlashHandler(sessionManager, null, config, learning, learningHome = home) { output.add(it) }
        handler.handle("/feedback list /proj", AgentSession(id = "s1"))
        output shouldHaveSize 1
        output[0] shouldContain "positive"
        output[0] shouldContain "great answer"
    }

    test("/feedback delete with no id shows usage") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"))
        val handler = SlashHandler(sessionManager, null, config, learning, learningHome = home) { output.add(it) }
        handler.handle("/feedback delete", AgentSession(id = "s1"))
        output shouldBe listOf("Usage: /feedback delete <id>")
    }

    test("/lessons shows disabled message when learning is not enabled") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/lessons list", AgentSession(id = "s1"))
        output shouldBe listOf("Learning is not enabled.")
    }

    test("/lessons list shows 'No lessons.' when empty") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"))
        val handler = SlashHandler(sessionManager, null, config, learning, learningHome = home) { output.add(it) }
        handler.handle("/lessons list /proj", AgentSession(id = "s1"))
        output shouldBe listOf("No lessons.")
    }

    test("/lessons archive with no id shows usage") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"))
        val handler = SlashHandler(sessionManager, null, config, learning, learningHome = home) { output.add(it) }
        handler.handle("/lessons archive", AgentSession(id = "s1"))
        output shouldBe listOf("Usage: /lessons archive <id>")
    }
})
