package dev.sophi.schedule.engine

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.schedule.model.RunOutcome
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
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

/** Two accounts of one kind are two mcp.json servers; a task's mcpServers is the one chosen when it was made. */
class ScheduleEngineMcpServersTest : FunSpec({
    fun tool(name: String) = object : Tool {
        override val name = name
        override val description = name
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE
        override suspend fun execute(argumentsJson: String) = "ok"
    }

    val registry = ToolRegistry()
        .register(tool("get_current_datetime"))
        .register(tool("work__list_issues"))
        .register(tool("personal__list_issues"))

    fun setup(provider: LLMProvider): Pair<ScheduleEngine, TaskStore> {
        val home = tempdir().toPath()
        val store = TaskStore(home.resolve("tasks.json"))
        val engine = ScheduleEngine(
            store, RunLog(home.resolve("runs.jsonl")), provider, registry,
            FileSessionManager(createTempDirectory("mcp-servers")), Notifier { _, _ -> },
            model = "m", contextWindowTokens = 100_000
        )
        return engine to store
    }

    fun capturing(requests: MutableList<CompletionRequest>): LLMProvider {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } answers { requests += firstArg<CompletionRequest>(); flowOf(StreamEvent.Content("done")) }
        return provider
    }

    test("a run sees only the MCP servers its task was made with, and every non-MCP tool") {
        val requests = mutableListOf<CompletionRequest>()
        val (engine, store) = setup(capturing(requests))
        val t = store.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p",
            mcpServers = setOf("work")))

        runBlocking { engine.runNow(t.id) }!!.outcome shouldBe RunOutcome.Succeeded

        requests.first().tools.map { it.name }.toSet() shouldBe setOf("get_current_datetime", "work__list_issues")
    }

    test("a task made without a choice sees every server, as before") {
        val requests = mutableListOf<CompletionRequest>()
        val (engine, store) = setup(capturing(requests))
        val t = store.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p"))

        runBlocking { engine.runNow(t.id) }

        requests.first().tools.map { it.name }.toSet() shouldBe registry.names().toSet()
    }

    test("a chosen server that is gone fails the run before any model call, naming the server") {
        val provider = mockk<LLMProvider>()
        val (engine, store) = setup(provider)
        val t = store.add(ScheduledTask(name = "t", trigger = Trigger.Manual, mode = TaskMode.Recurring, prompt = "p",
            mcpServers = setOf("work", "old-account")))

        val run = runBlocking { engine.runNow(t.id) }!!

        run.outcome.shouldBeInstanceOf<RunOutcome.Failed>().error shouldContain "old-account"
        verify(exactly = 0) { provider.stream(any()) }
    }
})
