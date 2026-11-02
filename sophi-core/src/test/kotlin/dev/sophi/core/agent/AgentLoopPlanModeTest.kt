package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.audit.AuditSink
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.PlanRecorder
import dev.sophi.core.dryrun.planStepStub
import dev.sophi.core.permissions.Effect
import dev.sophi.core.permissions.PermissionRule
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.tools.Unattended
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.withContext

class AgentLoopPlanModeTest : FunSpec({
    val config = AgentConfig(model = "test-model", systemPrompt = "You are helpful.")

    fun use(vararg calls: ToolCall) = LLMResponse.ToolUse(calls.toList(), TokenUsage(1, 0))
    val done = LLMResponse.Text("done", TokenUsage(1, 1))

    val requests = mutableListOf<CompletionRequest>()
    fun scripted(vararg rounds: LLMResponse): Pair<LLMProvider, SessionManager> {
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        var n = 0
        requests.clear()
        every { provider.stream(any()) } answers {
            requests += firstArg<CompletionRequest>()
            rounds[(n++).coerceAtMost(rounds.size - 1)].toStreamFlow()
        }
        every { sm.save(any()) } just Runs
        return provider to sm
    }

    class FakeTool(
        override val name: String,
        private val risk: RiskLevel = RiskLevel.DESTRUCTIVE,
        private val where: Reach = Reach.OUTSIDE_CHANGE,
        private val run: suspend (String) -> String = { "ok" },
    ) : Tool {
        var executed = 0
        override val description = "fake"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = risk
        override fun reach(argumentsJson: String) = where
        override suspend fun execute(argumentsJson: String): String { executed++; return run(argumentsJson) }
    }

    class CountingPolicy(private val answer: Boolean = true) : ConfirmationPolicy {
        var asked = 0
        override suspend fun confirm(requests: List<dev.sophi.core.tools.ConfirmationRequest>): Map<String, Boolean> {
            asked += requests.size
            return requests.associate { it.callId to answer }
        }
    }

    fun rules(vararg r: PermissionRule) = PermissionRules(r.toList(), emptyList(), null, null, emptySet())

    fun loop(
        provider: LLMProvider, sm: SessionManager, vararg tools: Tool,
        policy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
        grants: Set<String> = emptySet(),
        audit: AuditSink? = null,
        permissions: PermissionRules? = null,
    ) = AgentLoop(provider, ToolRegistry().apply { tools.forEach { register(it) } }, sm,
        confirmationPolicy = policy, grants = grants, contextWindowTokens = 100_000, audit = audit, permissions = permissions)

    fun toolMessages(round: Int) = requests[round].messages.filter { it.role == MessageRole.TOOL }

    test("an outside change is recorded, not run, and the model gets the stub") {
        val deactivate = FakeTool("okta__deactivate_user")
        val policy = CountingPolicy()
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__deactivate_user", """{"id":"00u9"}""")), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) { loop(provider, sm, deactivate, policy = policy).turn(AgentSession(id = "s"), "go", config) }

        deactivate.executed shouldBe 0
        policy.asked shouldBe 0
        toolMessages(1).single().content shouldBe planStepStub(1)
        recorder.steps.single().let { it.tool shouldBe "okta__deactivate_user"; it.args shouldBe """{"id":"00u9"}""" }
    }

    test("a granted outside change is still recorded") {
        val deactivate = FakeTool("okta__deactivate_user")
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__deactivate_user", "{}")), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) {
            loop(provider, sm, deactivate, grants = setOf("okta__deactivate_user")).turn(AgentSession(id = "s"), "go", config)
        }
        deactivate.executed shouldBe 0
        recorder.steps.single().allowedByRule shouldBe true
    }

    test("an allow rule doesn't run an outside change in plan mode") {
        val deactivate = FakeTool("okta__deactivate_user")
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__deactivate_user", "{}")), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) {
            loop(provider, sm, deactivate, permissions = rules(PermissionRule("okta__deactivate_user", null, Effect.ALLOW)))
                .turn(AgentSession(id = "s"), "go", config)
        }
        deactivate.executed shouldBe 0
        recorder.steps.single().allowedByRule shouldBe true
    }

    test("a deny rule still denies an outside change") {
        val deactivate = FakeTool("okta__deactivate_user")
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__deactivate_user", "{}")), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) {
            loop(provider, sm, deactivate, permissions = rules(PermissionRule("okta__deactivate_user", null, Effect.DENY)))
                .turn(AgentSession(id = "s"), "go", config)
        }
        recorder.steps shouldBe emptyList()
        toolMessages(1).single().content shouldContain "denied by a permission rule"
    }

    test("a hinted read before any change runs and is listed as a read") {
        val find = FakeTool("okta__find_user", where = Reach.OUTSIDE_HINTED_READ) { "00u9" }
        val policy = CountingPolicy()
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__find_user", """{"q":"jane"}""")), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) { loop(provider, sm, find, policy = policy).turn(AgentSession(id = "s"), "go", config) }

        find.executed shouldBe 1
        policy.asked shouldBe 0
        recorder.steps shouldBe emptyList()
        recorder.reads.single().let { it.viaHint shouldBe true; it.result shouldBe "00u9" }
    }

    test("a hinted read asks as today when trust is off") {
        val find = FakeTool("okta__find_user", where = Reach.OUTSIDE_HINTED_READ)
        val policy = CountingPolicy()
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__find_user", "{}")), done)
        withContext(PlanRecorder(PlanFirst.OUTSIDE, trustReadOnlyHints = false)) {
            loop(provider, sm, find, policy = policy).turn(AgentSession(id = "s"), "go", config)
        }
        policy.asked shouldBe 1
        find.executed shouldBe 1
    }

    test("a safeTools read runs and is listed as a read, not via the hint") {
        val list = FakeTool("okta__list_users", risk = RiskLevel.SAFE, where = Reach.OUTSIDE_READ)
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__list_users", "{}")), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) { loop(provider, sm, list).turn(AgentSession(id = "s"), "go", config) }
        list.executed shouldBe 1
        recorder.reads.single().viaHint shouldBe false
    }

    test("parallel outside changes in one round get steps 1 and 2 in call order") {
        val a = FakeTool("okta__deactivate_user")
        val b = FakeTool("github__remove_member")
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__deactivate_user", "{}"), ToolCall("c2", "github__remove_member", "{}")), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) { loop(provider, sm, a, b).turn(AgentSession(id = "s"), "go", config) }
        recorder.steps.map { it.n to it.tool } shouldBe listOf(1 to "okta__deactivate_user", 2 to "github__remove_member")
        toolMessages(1).map { it.content } shouldBe listOf(planStepStub(1), planStepStub(2))
    }

    test("local destructive calls still go through confirmation in OUTSIDE mode") {
        val rm = FakeTool("rm", where = Reach.LOCAL)
        val policy = CountingPolicy()
        val (provider, sm) = scripted(use(ToolCall("c1", "rm", "{}")), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) { loop(provider, sm, rm, policy = policy).turn(AgentSession(id = "s"), "go", config) }
        policy.asked shouldBe 1
        rm.executed shouldBe 1
        recorder.steps shouldBe emptyList()
    }

    test("EVERY mode records a local destructive call") {
        val rm = FakeTool("rm", where = Reach.LOCAL)
        val (provider, sm) = scripted(use(ToolCall("c1", "rm", "{}")), done)
        val recorder = PlanRecorder(PlanFirst.EVERY)
        withContext(recorder) { loop(provider, sm, rm).turn(AgentSession(id = "s"), "go", config) }
        rm.executed shouldBe 0
        recorder.steps.single().tool shouldBe "rm"
    }

    test("no recorder: an outside change asks as today") {
        val deactivate = FakeTool("okta__deactivate_user")
        val policy = CountingPolicy()
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__deactivate_user", "{}")), done)
        loop(provider, sm, deactivate, policy = policy).turn(AgentSession(id = "s"), "go", config)
        policy.asked shouldBe 1
        deactivate.executed shouldBe 1
    }

    test("a recorded call is audited as held with outcome recorded") {
        val entries = mutableListOf<AuditEntry>()
        val deactivate = FakeTool("okta__deactivate_user")
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__deactivate_user", "{}")), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) {
            loop(provider, sm, deactivate, audit = { entries += it }).turn(AgentSession(id = "s"), "go", config)
        }
        entries.single().let { it.outcome shouldBe "recorded"; it.decision shouldBe "held"; it.rule shouldBe "plan" }
        entries.single().plan shouldBe "${recorder.planId}#1"
        recorder.toPlan("go", "s").id shouldBe recorder.planId
    }

    test("unattended: a granted outside change is recorded, an ungranted one is denied") {
        val a = FakeTool("okta__deactivate_user")
        val b = FakeTool("github__remove_member")
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__deactivate_user", "{}"), ToolCall("c2", "github__remove_member", "{}")), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder + Unattended) {
            loop(provider, sm, a, b, policy = ConfirmationPolicy.DENY_ALL, grants = setOf("okta__deactivate_user"))
                .turn(AgentSession(id = "s"), "go", config)
        }
        recorder.steps.map { it.tool } shouldBe listOf("okta__deactivate_user")
        toolMessages(1)[1].content shouldContain "denied"
        b.executed shouldBe 0
    }

    test("unattended: a hinted read the run wasn't granted is denied, a granted one runs") {
        val find = FakeTool("okta__find_user", where = Reach.OUTSIDE_HINTED_READ)
        val search = FakeTool("slack__search", where = Reach.OUTSIDE_HINTED_READ)
        val (provider, sm) = scripted(use(ToolCall("c1", "okta__find_user", "{}"), ToolCall("c2", "slack__search", "{}")), done)
        withContext(PlanRecorder(PlanFirst.OUTSIDE) + Unattended) {
            loop(provider, sm, find, search, policy = ConfirmationPolicy.DENY_ALL, grants = setOf("slack__search"))
                .turn(AgentSession(id = "s"), "go", config)
        }
        find.executed shouldBe 0
        search.executed shouldBe 1
    }

    test("a nested loop's outside change lands in the parent turn's recorder") {
        val deactivate = FakeTool("okta__deactivate_user")
        val (innerProvider, innerSm) = scripted(use(ToolCall("i1", "okta__deactivate_user", "{}")), done)
        val inner = loop(innerProvider, innerSm, deactivate)
        val delegate = FakeTool("delegate", risk = RiskLevel.SAFE, where = Reach.LOCAL) {
            inner.turn(AgentSession(id = "child"), "do it", config).tip?.content ?: ""
        }
        val outerProvider = mockk<LLMProvider>()
        var n = 0
        every { outerProvider.stream(any()) } answers {
            (if (n++ == 0) use(ToolCall("o1", "delegate", "{}")) else done).toStreamFlow()
        }
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) { loop(outerProvider, innerSm, delegate).turn(AgentSession(id = "s"), "go", config) }
        deactivate.executed shouldBe 0
        recorder.steps.single().tool shouldBe "okta__deactivate_user"
    }
})
