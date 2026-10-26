package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.permissions.PermissionRulesException
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class RuntimeBuilderPermissionsTest : FunSpec({
    class Rm : Tool {
        var executed = 0
        override val name = "rm"
        override val description = "fake"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
        override suspend fun execute(argumentsJson: String): String { executed++; return "ok" }
    }

    fun builder(rm: Rm): RuntimeBuilder {
        val rounds = listOf<LLMResponse>(
            LLMResponse.ToolUse(listOf(ToolCall("c1", "rm", "{}")), TokenUsage(1, 0)),
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
        }.contextWindowTokens(100_000).tool(rm)
    }

    test("permissionRules() loads the global and project files and the built loop enforces a deny") {
        val home = tempdir().toPath()
        val project = tempdir().toPath()
        Files.writeString(home.resolve("permissions.json"), """[{"tool":"rm","effect":"deny"}]""")
        val rm = Rm()
        val got = mutableListOf<AuditEntry>()
        val runtime = builder(rm).audit { got += it }.permissionRules(project, home).build()
        runBlocking { runtime.turn(runtime.newSession(), "rm it") }

        rm.executed shouldBe 0
        runtime.permissionRules!!.active.size shouldBe 1
        got.single().rule shouldBe "rule:deny"
    }

    test("a malformed permissions file throws PermissionRulesException by build()") {
        val home = tempdir().toPath()
        Files.writeString(home.resolve("permissions.json"), "{nope")
        shouldThrow<PermissionRulesException> { builder(Rm()).permissionRules(tempdir().toPath(), home).build() }
    }

    test("without permissionRules the runtime has none") {
        builder(Rm()).build().permissionRules shouldBe null
    }

    test("a scheduled task run through runtime.scheduleEngine enforces the deny") {
        val home = tempdir().toPath()
        Files.writeString(home.resolve("permissions.json"), """[{"tool":"rm","effect":"deny"}]""")
        val rm = Rm()
        val runtime = builder(rm).permissionRules(tempdir().toPath(), home).build()
        val dir = tempdir().toPath()
        val tasks = dev.sophi.schedule.store.TaskStore(dir.resolve("tasks.json"))
        val task = tasks.add(dev.sophi.schedule.model.ScheduledTask(
            name = "t", trigger = dev.sophi.schedule.model.Trigger.Manual,
            mode = dev.sophi.schedule.model.TaskMode.Recurring, prompt = "rm it", toolGrants = setOf("rm")
        ))
        val engine = runtime.scheduleEngine(tasks, dev.sophi.schedule.store.RunLog(dir.resolve("runs.jsonl")), dev.sophi.schedule.notify.NoopNotifier)
        runBlocking { engine.runNow(task.id) }
        rm.executed shouldBe 0
    }
})
