package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.tools.ConfirmationPolicy
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
import kotlinx.coroutines.runBlocking
import kotlin.io.path.readLines

class RuntimeBuilderAuditTest : FunSpec({
    // One write_file call, then a text answer.
    fun builder(): RuntimeBuilder {
        val rounds = listOf<LLMResponse>(
            LLMResponse.ToolUse(listOf(ToolCall("c1", "write_file", """{"path":"x.txt","content":"hi"}""")), TokenUsage(1, 0)),
            LLMResponse.Text("done", TokenUsage(1, 1)),
        )
        var n = 0
        val llm = mockk<LLMProvider>()
        every { llm.stream(any()) } answers { rounds[n++].toStreamFlow() }
        return RuntimeBuilder().apply {
            provider = llm
            sessionsDir = tempdir().toPath()
            model = "test-model"
            confirmationPolicy(ConfirmationPolicy.ALLOW_ALL)
        }.contextWindowTokens(100_000).builtinTools(tempdir().toPath())
    }

    test("with auditLog, a turn's tool call is appended to the file") {
        val file = tempdir().toPath().resolve("audit.jsonl")
        val runtime = builder().auditLog(file).build()
        val id = runBlocking { runtime.newSession().also { runtime.turn(it, "write it") } }

        val lines = file.readLines()
        lines.size shouldBe 1
        lines[0].contains(""""tool":"write_file"""") shouldBe true
        lines[0].contains(""""session":"$id"""") shouldBe true
    }

    test("without auditLog the runtime has no auditSink") {
        val runtime = builder().build()
        runBlocking { runtime.turn(runtime.newSession(), "write it") }
        runtime.auditSink shouldBe null
    }

    test("audit(sink) receives the entries") {
        val got = mutableListOf<AuditEntry>()
        val runtime = builder().audit { got += it }.build()
        runBlocking { runtime.turn(runtime.newSession(), "write it") }
        got.single().tool shouldBe "write_file"
        got.single().rule shouldBe "allow-all"
    }

    test("a scheduled task run through runtime.scheduleEngine reaches the audit sink") {
        val got = mutableListOf<AuditEntry>()
        val runtime = builder().audit { got += it }.build()
        val home = tempdir().toPath()
        val tasks = TaskStore(home.resolve("tasks.json"))
        val task = tasks.add(ScheduledTask(
            name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "write it",
            toolGrants = setOf("write_file")
        ))
        val engine = runtime.scheduleEngine(tasks, RunLog(home.resolve("runs.jsonl")), NoopNotifier)
        runBlocking { engine.runNow(task.id) }
        got.single().let { it.tool shouldBe "write_file"; it.rule shouldBe "grant" }
    }
})
