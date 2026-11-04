package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.permissions.Effect
import dev.sophi.core.permissions.PermissionRule
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.CodeModeTool
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext

class AgentLoopToolCallScopeTest : FunSpec({
    val config = AgentConfig(model = "m", systemPrompt = "s")

    class Risky : Tool {
        var executed = 0
        override val name = "risky"
        override val description = "risky"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = RiskLevel.CAUTION
        override suspend fun execute(argumentsJson: String): String { executed++; return "ok" }
    }

    /** Calls `risky` through the loop's ToolCallScope and returns what came back. */
    class Driver : Tool {
        var result: String? = null
        override val name = "driver"
        override val description = "driver"
        override val parametersJson = "{}"
        override suspend fun execute(argumentsJson: String): String =
            currentCoroutineContext()[ToolCallScope]!!.call("risky", "{}").also { result = it }
    }

    fun provider(first: ToolCall = ToolCall("c1", "driver", "{}")): Pair<LLMProvider, SessionManager> {
        val p = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        var n = 0
        val rounds = listOf(LLMResponse.ToolUse(listOf(first), TokenUsage(1, 0)), LLMResponse.Text("done", TokenUsage(1, 1)))
        every { p.stream(any()) } answers { rounds[(n++).coerceAtMost(1)].toStreamFlow() }
        every { sm.save(any()) } just Runs
        return p to sm
    }

    data class Run(val driver: Driver, val risky: Risky, val audit: List<AuditEntry>, val started: List<String>)

    suspend fun run(policy: ConfirmationPolicy, rules: PermissionRules? = null, hooks: LoopHooks? = null): Run {
        val (p, sm) = provider()
        val driver = Driver(); val risky = Risky()
        val audit = mutableListOf<AuditEntry>(); val started = mutableListOf<String>()
        AgentLoop(p, ToolRegistry().register(driver).register(risky), sm, confirmationPolicy = policy,
            contextWindowTokens = 100_000, audit = { audit += it }, permissions = rules, hooks = hooks)
            .turn(AgentSession(id = "s"), "go", config) { e -> if (e is TurnEvent.ToolCallStarted) started += e.name }
        return Run(driver, risky, audit, started)
    }

    test("a call made through ToolCallScope is confirmed like the model's own") {
        val r = run(ConfirmationPolicy.DENY_ALL)
        r.driver.result!! shouldStartWith "Error: Tool 'risky' execution denied"
        r.risky.executed shouldBe 0
        r.audit.single { it.tool == "risky" }.outcome shouldBe "denied"
    }

    test("a call made through ToolCallScope obeys permission rules") {
        val r = run(ConfirmationPolicy.ALLOW_ALL, PermissionRules(listOf(PermissionRule("risky", null, Effect.DENY)), emptyList(), null, null, emptySet()))
        r.driver.result!! shouldStartWith "Error: Tool 'risky' denied by a permission rule"
        r.risky.executed shouldBe 0
    }

    test("a call made through ToolCallScope can be vetoed by a hook") {
        val veto = object : LoopHooks {
            override suspend fun beforeTool(sessionId: String, name: String, argumentsJson: String) =
                if (name == "risky") HookResult.Veto("no") else HookResult.Continue
        }
        val r = run(ConfirmationPolicy.ALLOW_ALL, hooks = veto)
        r.driver.result!! shouldStartWith "Error: Tool 'risky' vetoed by a hook: no"
        r.risky.executed shouldBe 0
    }

    test("an allowed call made through ToolCallScope runs, is audited and reported") {
        val r = run(ConfirmationPolicy.ALLOW_ALL)
        r.driver.result shouldBe "ok"
        r.risky.executed shouldBe 1
        r.audit.map { it.tool }.toSet() shouldBe setOf("driver", "risky")
        r.started shouldBe listOf("driver", "risky")
    }

    test("a code_mode script's call is denied and audited like the model's own") {
        val (p, sm) = provider(ToolCall("c1", "code_mode", """{"code":"try { tools.risky({}) } catch (e) { return e.message }"}"""))
        val risky = Risky()
        val audit = mutableListOf<AuditEntry>()
        val session = AgentLoop(p, ToolRegistry().register(CodeModeTool()).register(risky), sm,
            confirmationPolicy = ConfirmationPolicy.DENY_ALL, contextWindowTokens = 100_000, audit = { audit += it })
            .turn(AgentSession(id = "s"), "go", config)
        risky.executed shouldBe 0
        audit.single { it.tool == "risky" }.outcome shouldBe "denied"
        audit.single { it.tool == "code_mode" }.result shouldStartWith "Error: Tool 'risky' execution denied"
        session.entries.any { it.metadata["toolName"] == "risky" } shouldBe false
    }
})
