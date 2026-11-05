package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.audit.noteDecision
import dev.sophi.core.permissions.Effect
import dev.sophi.core.permissions.PermissionRule
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.GuardianScope
import dev.sophi.core.tools.LlmGuardian
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.withContext

class AgentLoopGuardianTest : FunSpec({
    val config = AgentConfig(model = "test-model", systemPrompt = "You are helpful.")
    val done = LLMResponse.Text("done", TokenUsage(1, 1))

    val requests = mutableListOf<CompletionRequest>()
    fun scripted(call: ToolCall): Pair<LLMProvider, SessionManager> {
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        var n = 0
        requests.clear()
        val rounds = listOf(LLMResponse.ToolUse(listOf(call), TokenUsage(1, 0)), done)
        every { provider.stream(any()) } answers {
            requests += firstArg<CompletionRequest>()
            rounds[(n++).coerceAtMost(1)].toStreamFlow()
        }
        every { sm.save(any()) } just Runs
        return provider to sm
    }

    class FakeTool(override val name: String, private val risk: RiskLevel) : Tool {
        var executed = 0
        override val description = "fake"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = risk
        override suspend fun execute(argumentsJson: String): String { executed++; return "ok" }
    }

    fun guardProvider(reply: String): LLMProvider = mockk<LLMProvider>().also {
        coEvery { it.complete(any()) } returns LLMResponse.Text(reply, TokenUsage(1, 1))
    }
    fun allow(tool: String) = PermissionRules(listOf(PermissionRule(tool, null, Effect.ALLOW)), emptyList(), null, null, emptySet())

    fun loop(
        provider: LLMProvider, sm: SessionManager, tool: Tool, guardian: LlmGuardian?,
        policy: ConfirmationPolicy = ConfirmationPolicy.DENY_ALL, grants: Set<String> = emptySet(),
        entries: MutableList<AuditEntry>? = null, permissions: PermissionRules? = null,
    ) = AgentLoop(provider, ToolRegistry().register(tool), sm, confirmationPolicy = policy, grants = grants,
        contextWindowTokens = 100_000, audit = entries?.let { e -> { e += it } }, permissions = permissions, guardian = guardian)

    fun toolMessage() = requests[1].messages.last { it.role == MessageRole.TOOL }.content

    test("DENY stops a granted destructive call and is audited") {
        val entries = mutableListOf<AuditEntry>()
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val (provider, sm) = scripted(ToolCall("c1", "rm", "{}"))
        loop(provider, sm, rm, LlmGuardian(guardProvider("DENY: no"), "g"), grants = setOf("rm"), entries = entries)
            .turn(AgentSession(id = "s"), "go", config)

        rm.executed shouldBe 0
        toolMessage() shouldContain "denied by the guardian: no"
        entries.single().rule shouldBe "guardian"
        entries.single().guardian shouldBe "deny: no"
        entries.single().outcome shouldBe "denied"
    }

    test("ASK sends an allow-rule call to the policy as an ask, with the reason in the preview") {
        val entries = mutableListOf<AuditEntry>()
        val seen = mutableListOf<ConfirmationRequest>()
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val person = ConfirmationPolicy { reqs -> seen += reqs; reqs.associate { noteDecision(it.callId, "user"); it.callId to true } }
        val (provider, sm) = scripted(ToolCall("c1", "rm", "{}"))
        loop(provider, sm, rm, LlmGuardian(guardProvider("ASK: unsure"), "g"), policy = person, entries = entries, permissions = allow("rm"))
            .turn(AgentSession(id = "s"), "go", config)

        seen.single().askRule shouldBe true
        seen.single().alwaysAllow shouldBe null
        seen.single().preview!! shouldContain "guardian: unsure"
        rm.executed shouldBe 1
        entries.single().guardian shouldBe "ask: unsure"
        entries.single().rule shouldBe "user"
    }

    test("ASK with nobody to answer is denied") {
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val (provider, sm) = scripted(ToolCall("c1", "rm", "{}"))
        loop(provider, sm, rm, LlmGuardian(guardProvider("ASK: unsure"), "g"), grants = setOf("rm"))
            .turn(AgentSession(id = "s"), "go", config)

        rm.executed shouldBe 0
    }

    test("ALLOW changes nothing but is audited") {
        val entries = mutableListOf<AuditEntry>()
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val (provider, sm) = scripted(ToolCall("c1", "rm", "{}"))
        loop(provider, sm, rm, LlmGuardian(guardProvider("ALLOW: fine"), "g"), grants = setOf("rm"), entries = entries)
            .turn(AgentSession(id = "s"), "go", config)

        rm.executed shouldBe 1
        entries.single().rule shouldBe "grant"
        entries.single().guardian shouldBe "allow: fine"
    }

    test("a call a person approved is not reviewed") {
        val entries = mutableListOf<AuditEntry>()
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val g = guardProvider("DENY: no")
        val person = ConfirmationPolicy { reqs -> reqs.associate { noteDecision(it.callId, "user"); it.callId to true } }
        val (provider, sm) = scripted(ToolCall("c1", "rm", "{}"))
        loop(provider, sm, rm, LlmGuardian(g, "g"), policy = person, entries = entries)
            .turn(AgentSession(id = "s"), "go", config)

        rm.executed shouldBe 1
        coVerify(exactly = 0) { g.complete(any()) }
        entries.single().guardian shouldBe null
    }

    test("a CAUTION call is not reviewed") {
        val edit = FakeTool("edit", RiskLevel.CAUTION)
        val g = guardProvider("DENY: no")
        val (provider, sm) = scripted(ToolCall("c1", "edit", "{}"))
        loop(provider, sm, edit, LlmGuardian(g, "g"), grants = setOf("edit"))
            .turn(AgentSession(id = "s"), "go", config)

        edit.executed shouldBe 1
        coVerify(exactly = 0) { g.complete(any()) }
    }

    test("a failing guardian asks a person, never allows") {
        val seen = mutableListOf<ConfirmationRequest>()
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val g = mockk<LLMProvider>().also { coEvery { it.complete(any()) } throws RuntimeException("down") }
        val person = ConfirmationPolicy { reqs -> seen += reqs; reqs.associate { it.callId to false } }
        val (provider, sm) = scripted(ToolCall("c1", "rm", "{}"))
        loop(provider, sm, rm, LlmGuardian(g, "g"), policy = person, grants = setOf("rm"))
            .turn(AgentSession(id = "s"), "go", config)

        seen.single().askRule shouldBe true
        rm.executed shouldBe 0
    }

    test("a secret in the reason is redacted in the audit log") {
        val entries = mutableListOf<AuditEntry>()
        val key = "sk-ant-api03-" + "a".repeat(40)
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val (provider, sm) = scripted(ToolCall("c1", "rm", "{}"))
        loop(provider, sm, rm, LlmGuardian(guardProvider("DENY: leaks $key"), "g"), grants = setOf("rm"), entries = entries)
            .turn(AgentSession(id = "s"), "go", config)

        entries.single().guardian!! shouldNotContain key
    }

    test("a nested loop without a guardian uses the enclosing one") {
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val (provider, sm) = scripted(ToolCall("c1", "rm", "{}"))
        val nested = loop(provider, sm, rm, guardian = null, grants = setOf("rm"))
        withContext(GuardianScope(LlmGuardian(guardProvider("DENY: no"), "g"))) {
            nested.turn(AgentSession(id = "s"), "go", config)
        }
        rm.executed shouldBe 0
    }
})
