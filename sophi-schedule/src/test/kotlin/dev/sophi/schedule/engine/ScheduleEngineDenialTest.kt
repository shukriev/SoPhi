package dev.sophi.schedule.engine

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import dev.sophi.core.agent.TurnStopReason
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

private const val STOPPED = "Stopped after 3 blocked actions: danger. Allow these tools on the goal to let it do them."

class ScheduleEngineDenialTest : FunSpec({
    val danger = object : Tool {
        override val name = "danger"
        override val description = "risky"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
        override suspend fun execute(argumentsJson: String) = "ran"
    }

    fun denied(vararg ids: String) = flowOf(StreamEvent.ToolCallsReady(ids.map { ToolCall(it, "danger", "{}") }))

    fun engine(provider: LLMProvider, notified: MutableList<RunRecord> = mutableListOf()): Pair<ScheduleEngine, TaskStore> {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        val notifier = object : Notifier { override fun notify(task: ScheduledTask, run: RunRecord) { notified += run } }
        val e = ScheduleEngine(store, RunLog(home.resolve("runs.jsonl")), provider, ToolRegistry().register(danger),
            FileSessionManager(createTempDirectory("schedule-denial-test")), notifier, model = "m", contextWindowTokens = 100_000)
        return e to store
    }

    val audited = java.util.concurrent.CopyOnWriteArrayList<dev.sophi.core.audit.AuditEntry>()
    fun engineWithSessions(provider: LLMProvider): Triple<ScheduleEngine, TaskStore, FileSessionManager> {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        val sessions = FileSessionManager(createTempDirectory("schedule-denial-test"))
        val e = ScheduleEngine(store, RunLog(home.resolve("runs.jsonl")), provider, ToolRegistry().register(danger),
            sessions, object : Notifier { override fun notify(task: ScheduledTask, run: RunRecord) {} }, model = "m", contextWindowTokens = 100_000,
            audit = { audited += it })
        return Triple(e, store, sessions)
    }

    fun recurring(store: TaskStore) =
        store.add(ScheduledTask(name = "t", trigger = Trigger.Interval(3600), mode = TaskMode.Recurring, prompt = "run it"))

    test("the 3rd blocked action stops an unattended run, says which tools, and notifies") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(denied("c1", "c2", "c3"), flowOf(StreamEvent.Content("done")))
        val notified = mutableListOf<RunRecord>()
        val (e, store) = engine(provider, notified)

        val record = runBlocking { e.runNow(recurring(store).id) }!!

        record.outcome shouldBe RunOutcome.Failed(STOPPED)
        record.summary shouldBe STOPPED
        notified.single().outcome shouldBe RunOutcome.Failed(STOPPED)
        verify(exactly = 1) { provider.stream(any()) }
    }

    test("the report shows each blocked call's arguments with secrets masked, and the session records why it stopped") {
        val provider = mockk<LLMProvider>()
        val secret = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789"
        val calls = listOf(ToolCall("c1", "danger", "{\"path\":\"/etc/hosts\"}"),
            ToolCall("c2", "danger", "{\"api_key\":\"$secret\"}"), ToolCall("c3", "danger", "{}"))
        every { provider.stream(any()) } returnsMany listOf(flowOf(StreamEvent.ToolCallsReady(calls)), flowOf(StreamEvent.Content("done")))
        val (e, store, sessions) = engineWithSessions(provider)

        val record = runBlocking { e.runNow(recurring(store).id) }!!

        record.summary shouldContain "danger {\"path\":\"/etc/hosts\"}"
        record.summary shouldContain "[REDACTED]"
        record.summary shouldNotContain secret
        val last = sessions.load(record.sessionId!!).entries.last()
        last.metadata["stopReason"] shouldBe TurnStopReason.DeniedUnattended.name
        last.content shouldBe record.summary
        audited.map { it.decision } shouldBe listOf("denied", "denied", "denied")
    }

    test("an unattended denial tells the model nobody can approve it") {
        val provider = mockk<LLMProvider>()
        val requests = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(capture(requests)) } returnsMany listOf(denied("c1"), flowOf(StreamEvent.Content("done")))
        val (e, store) = engine(provider)

        runBlocking { e.runNow(recurring(store).id) }

        requests.last().messages.last().content shouldContain "nobody is here to approve"
    }

    test("two blocked actions don't stop the run") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(denied("c1"), denied("c2"), flowOf(StreamEvent.Content("done")))
        val (e, store) = engine(provider)

        val record = runBlocking { e.runNow(recurring(store).id) }!!

        record.outcome shouldBe RunOutcome.Succeeded
        record.summary shouldBe "done"
    }

    test("blocked actions add up across a goal's steps") {
        val provider = mockk<LLMProvider>()
        var turn = 0
        every { provider.stream(any()) } answers { if (turn++ % 2 == 0) denied("a$turn", "b$turn") else flowOf(StreamEvent.Content("step done")) }
        coEvery { provider.complete(any()) } answers {
            val prompt = firstArg<dev.sophi.ai.api.CompletionRequest>().messages.joinToString { it.content }
            val text = when {
                "YES or NO" in prompt -> "NO"
                "\"steps\"" in prompt || "steps" in prompt.lowercase() && "json" in prompt.lowercase() ->
                    """{"steps":[{"id":"s1","instruction":"first"},{"id":"s2","instruction":"second","dependsOn":["s1"]}]}"""
                else -> "1.0"
            }
            LLMResponse.Text(text, TokenUsage(1, 1))
        }
        val (e, store) = engine(provider)
        val task = store.add(ScheduledTask(name = "g", trigger = Trigger.Interval(3600),
            mode = TaskMode.Goal(StopCondition.LlmJudged, maxIterations = 8), prompt = "reach the goal"))

        val record = runBlocking { e.runNow(task.id) }!!

        record.outcome shouldBe RunOutcome.Failed(STOPPED)
    }
})
