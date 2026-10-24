package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.audit.ActorContext
import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.audit.AuditSink
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentLoopAuditTest : FunSpec({
    val config = AgentConfig(model = "test-model", systemPrompt = "You are helpful.")

    fun use(vararg calls: ToolCall) = LLMResponse.ToolUse(calls.toList(), TokenUsage(1, 0))
    val done = LLMResponse.Text("done", TokenUsage(1, 1))

    fun scripted(vararg rounds: LLMResponse): Pair<LLMProvider, SessionManager> {
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        var n = 0
        every { provider.stream(any()) } answers { rounds[(n++).coerceAtMost(rounds.size - 1)].toStreamFlow() }
        every { sm.save(any()) } just Runs
        return provider to sm
    }

    class FakeTool(
        override val name: String,
        private val risk: RiskLevel = RiskLevel.SAFE,
        private val run: suspend (String) -> String = { "ok" },
    ) : Tool {
        var executed = 0
        override val description = "fake"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = risk
        override suspend fun execute(argumentsJson: String): String { executed++; return run(argumentsJson) }
    }

    fun loop(
        provider: LLMProvider, sm: SessionManager, vararg tools: Tool,
        policy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
        grants: Set<String> = emptySet(),
        audit: AuditSink? = null,
    ) = AgentLoop(provider, ToolRegistry().apply { tools.forEach { register(it) } }, sm,
        confirmationPolicy = policy, grants = grants, contextWindowTokens = 100_000, audit = audit)

    test("each tool call writes one audit entry with the agreed fields") {
        val entries = mutableListOf<AuditEntry>()
        val (provider, sm) = scripted(use(ToolCall("c1", "look", "{}"), ToolCall("c2", "rm", "{}")), done)
        loop(provider, sm, FakeTool("look"), FakeTool("rm", RiskLevel.DESTRUCTIVE), audit = { entries += it })
            .turn(AgentSession(id = "s1"), "go", config)

        entries shouldHaveSize 2
        entries.associate { it.tool to it.rule } shouldBe mapOf("look" to "safe", "rm" to "allow-all")
        entries.map { it.risk }.toSet() shouldBe setOf(RiskLevel.SAFE, RiskLevel.DESTRUCTIVE)
        entries.forEach {
            it.actor shouldBe "local"
            it.tenant shouldBe null
            it.session shouldBe "s1"
            it.decision shouldBe "allowed"
            it.outcome shouldBe "ok"
            it.result shouldBe "ok"
        }
    }

    test("a denied call is audited and not executed") {
        val entries = mutableListOf<AuditEntry>()
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val (provider, sm) = scripted(use(ToolCall("c1", "rm", "{}")), done)
        loop(provider, sm, rm, policy = ConfirmationPolicy.DENY_ALL, audit = { entries += it })
            .turn(AgentSession(id = "s"), "go", config)

        rm.executed shouldBe 0
        val e = entries.single()
        e.decision shouldBe "denied"
        e.outcome shouldBe "denied"
        e.rule shouldBe "deny-all"
        e.result shouldBe ""
    }

    test("a granted tool is audited with rule grant") {
        val entries = mutableListOf<AuditEntry>()
        val (provider, sm) = scripted(use(ToolCall("c1", "rm", "{}")), done)
        loop(provider, sm, FakeTool("rm", RiskLevel.DESTRUCTIVE), policy = ConfirmationPolicy.DENY_ALL,
            grants = setOf("rm"), audit = { entries += it }).turn(AgentSession(id = "s"), "go", config)

        entries.single().rule shouldBe "grant"
        entries.single().decision shouldBe "allowed"
    }

    test("a failing tool is audited with outcome error") {
        val entries = mutableListOf<AuditEntry>()
        val (provider, sm) = scripted(use(ToolCall("c1", "boom", "{}")), done)
        loop(provider, sm, FakeTool("boom") { throw IllegalStateException("kaput") }, audit = { entries += it })
            .turn(AgentSession(id = "s"), "go", config)

        entries.single().outcome shouldBe "error"
        entries.single().decision shouldBe "allowed"
    }

    test("arguments and results are redacted and capped") {
        val entries = mutableListOf<AuditEntry>()
        val args = """{"password":"hunter2","pad":"${"x".repeat(5000)}"}"""
        val result = "sk-abcdefghijklmnopqrstuvwx " + "y".repeat(1000 - 28)
        val (provider, sm) = scripted(use(ToolCall("c1", "look", args)), done)
        loop(provider, sm, FakeTool("look") { result }, audit = { entries += it })
            .turn(AgentSession(id = "s"), "go", config)

        val e = entries.single()
        e.args shouldNotContain "hunter2"
        e.result shouldNotContain "sk-abcdefghijklmnopqrstuvwx"
        e.args.length shouldBeLessThanOrEqual 4000
        e.result.length shouldBeLessThanOrEqual 500
    }

    test("ActorContext sets actor and tenant") {
        val entries = mutableListOf<AuditEntry>()
        val (provider, sm) = scripted(use(ToolCall("c1", "look", "{}")), done)
        val l = loop(provider, sm, FakeTool("look"), audit = { entries += it })
        withContext(ActorContext("alice", "acme")) { l.turn(AgentSession(id = "s"), "go", config) }

        entries.single().actor shouldBe "alice"
        entries.single().tenant shouldBe "acme"
    }

    test("an audit sink that throws does not change the turn") {
        fun turnWith(audit: AuditSink?): List<String> {
            val (provider, sm) = scripted(use(ToolCall("c1", "look", "{}"), ToolCall("c2", "rm", "{}")), done)
            val session = kotlinx.coroutines.runBlocking {
                loop(provider, sm, FakeTool("look") { "seen" }, FakeTool("rm", RiskLevel.DESTRUCTIVE) { "gone" },
                    audit = audit).turn(AgentSession(id = "s"), "go", config)
            }
            return session.branch().filter { it.role == EntryRole.TOOL_RESULT || it.role == EntryRole.ASSISTANT }
                .map { it.content }
        }
        val withThrowing = turnWith { throw IllegalStateException("disk full") }
        withThrowing shouldBe turnWith(null)
        withThrowing.last() shouldBe "done"
    }

    test("a nested loop inherits the audit sink from the coroutine context") {
        val entries = mutableListOf<AuditEntry>()
        val (innerProvider, innerSm) = scripted(use(ToolCall("i1", "look", "{}")), done)
        val inner = loop(innerProvider, innerSm, FakeTool("look"))
        val outerTool = FakeTool("delegate") {
            inner.turn(AgentSession(id = "inner"), "sub", config)
            "delegated"
        }
        val (provider, sm) = scripted(use(ToolCall("o1", "delegate", "{}")), done)
        loop(provider, sm, outerTool, audit = { entries += it }).turn(AgentSession(id = "outer"), "go", config)

        entries.map { it.session to it.tool } shouldBe listOf("inner" to "look", "outer" to "delegate")
    }

    test("a call cancelled mid-run is audited as cancelled and the turn ends as stopped") {
        val entries = mutableListOf<AuditEntry>()
        val started = CompletableDeferred<Unit>()
        val (provider, sm) = scripted(use(ToolCall("c1", "hang", "{}")), done)
        val l = loop(provider, sm, FakeTool("hang") { started.complete(Unit); awaitCancellation() },
            audit = { entries += it })
        val session = AgentSession(id = "s")

        coroutineScope {
            val job = launch { l.turn(session, "go", config) }
            started.await()
            job.cancelAndJoin()
        }

        val e = entries.single()
        e.outcome shouldBe "cancelled"
        e.decision shouldBe "allowed"
        e.result shouldBe ""
        session.branch().last().metadata["stopReason"] shouldBe TurnStopReason.UserStopped.name
    }

    test("a decision the policy did not note is audited with rule policy") {
        val entries = mutableListOf<AuditEntry>()
        val (provider, sm) = scripted(use(ToolCall("c1", "rm", "{}")), done)
        loop(provider, sm, FakeTool("rm", RiskLevel.DESTRUCTIVE),
            policy = { requests -> requests.associate { it.callId to true } }, audit = { entries += it })
            .turn(AgentSession(id = "s"), "go", config)

        entries.single().rule shouldBe "policy"
    }

    test("a secret straddling the truncation boundary leaves no fragment") {
        val entries = mutableListOf<AuditEntry>()
        val key = "sk-abcdefghijklmnopqrstuvwx"
        // {"pad":" is 8 chars, so the key spans arg chars 3990..4017 and result chars 490..517.
        val args = """{"pad":"${"x".repeat(3982)} $key ${"x".repeat(100)}"}"""
        val result = "y".repeat(489) + " " + key + " " + "y".repeat(100) // the token pattern needs a non-alphanumeric before it
        val (provider, sm) = scripted(use(ToolCall("c1", "look", args)), done)
        loop(provider, sm, FakeTool("look") { result }, audit = { entries += it })
            .turn(AgentSession(id = "s"), "go", config)

        val e = entries.single()
        e.args shouldNotContain "sk-abc"
        e.result shouldNotContain "sk-abc"
        e.args shouldNotContain "abcdefghij"
        e.result shouldNotContain "abcdefghij"
    }

    test("a turn cancelled while ToolCallFinished is delivered still audits the completed call once") {
        val entries = mutableListOf<AuditEntry>()
        val (provider, sm) = scripted(use(ToolCall("c1", "look", "{}")), done)
        val l = loop(provider, sm, FakeTool("look"), audit = { entries += it })

        try {
            l.turn(AgentSession(id = "s"), "go", config) { event ->
                if (event is TurnEvent.ToolCallFinished) throw kotlinx.coroutines.CancellationException("stopped")
            }
        } catch (_: kotlinx.coroutines.CancellationException) {}

        entries.single().outcome shouldBe "ok"
    }
})
