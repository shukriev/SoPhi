package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.PlanRecorder
import dev.sophi.core.dryrun.planStepStub
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.withContext

class AgentLoopHooksTest : FunSpec({
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

    /** Harmless with `{"cmd":"ls"}`; anything else is a destructive change outside the workspace. */
    class Shell : Tool {
        val ran = mutableListOf<String>()
        override val name = "sh"
        override val description = "fake"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = if ("ls" in argumentsJson) RiskLevel.SAFE else RiskLevel.DESTRUCTIVE
        override fun reach(argumentsJson: String) = if ("ls" in argumentsJson) Reach.LOCAL else Reach.OUTSIDE_CHANGE
        override suspend fun execute(argumentsJson: String): String { ran += argumentsJson; return "ran $argumentsJson" }
    }

    val ls = ToolCall("c1", "sh", """{"cmd":"ls"}""")
    val rm = """{"cmd":"rm -rf /"}"""

    fun loop(provider: LLMProvider, sm: SessionManager, tool: Tool, hooks: LoopHooks,
             policy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL, audit: MutableList<AuditEntry> = mutableListOf()) =
        AgentLoop(provider, ToolRegistry().apply { register(tool) }, sm, confirmationPolicy = policy,
            contextWindowTokens = 100_000, audit = { audit += it }, hooks = hooks)

    fun toolMessage(round: Int) = requests[round].messages.last { it.role == MessageRole.TOOL }.content

    test("a veto stops the call, reaches the model as an error, and is audited") {
        val sh = Shell()
        val audit = mutableListOf<AuditEntry>()
        val hooks = object : LoopHooks {
            override suspend fun beforeTool(sessionId: String, name: String, argumentsJson: String) = HookResult.Veto("not today")
        }
        val (provider, sm) = scripted(use(ls), done)
        loop(provider, sm, sh, hooks, audit = audit).turn(AgentSession(id = "s"), "go", config)

        sh.ran shouldBe emptyList()
        toolMessage(1) shouldContain "Error:"
        toolMessage(1) shouldContain "not today"
        audit.single().let { it.decision shouldBe "denied"; it.rule shouldBe "hook" }
    }

    test("a rewrite can't launder a call past confirmation: the rewritten args are asked about") {
        val sh = Shell()
        val asked = mutableListOf<ConfirmationRequest>()
        val audit = mutableListOf<AuditEntry>()
        val hooks = object : LoopHooks {
            override suspend fun beforeTool(sessionId: String, name: String, argumentsJson: String) = HookResult.Rewrite(rm)
        }
        val (provider, sm) = scripted(use(ls), done)
        loop(provider, sm, sh, hooks, policy = { r -> asked += r; r.associate { it.callId to false } }, audit = audit)
            .turn(AgentSession(id = "s"), "go", config)

        sh.ran shouldBe emptyList()
        asked.single().let { it.argumentsJson shouldBe rm; it.riskLevel shouldBe RiskLevel.DESTRUCTIVE }
        audit.single().decision shouldBe "denied"
    }

    test("a rewrite can't launder a call past plan-first: the rewritten change is held") {
        val sh = Shell()
        val hooks = object : LoopHooks {
            override suspend fun beforeTool(sessionId: String, name: String, argumentsJson: String) = HookResult.Rewrite(rm)
        }
        val (provider, sm) = scripted(use(ls), done)
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        withContext(recorder) { loop(provider, sm, sh, hooks).turn(AgentSession(id = "s"), "go", config) }

        sh.ran shouldBe emptyList()
        recorder.steps.single().args shouldBe rm
        toolMessage(1) shouldBe planStepStub(1)
    }

    test("an allowed rewrite runs with the new args, and AFTER_TOOL sees them") {
        val sh = Shell()
        val after = mutableListOf<String>()
        val hooks = object : LoopHooks {
            override suspend fun beforeTool(sessionId: String, name: String, argumentsJson: String) = HookResult.Rewrite("""{"cmd":"ls -la"}""")
            override suspend fun afterTool(sessionId: String, name: String, argumentsJson: String, result: String, failed: Boolean, durationMillis: Long) {
                after += "$sessionId $argumentsJson $result $failed"
            }
        }
        val (provider, sm) = scripted(use(ls), done)
        loop(provider, sm, sh, hooks).turn(AgentSession(id = "s"), "go", config)

        sh.ran shouldBe listOf("""{"cmd":"ls -la"}""")
        after shouldBe listOf("""s {"cmd":"ls -la"} ran {"cmd":"ls -la"} false""")
    }

    test("ON_CONFIRMATION can deny before the policy is asked, and the denial is audited") {
        val sh = Shell()
        var policyAsked = 0
        val audit = mutableListOf<AuditEntry>()
        val hooks = object : LoopHooks {
            override suspend fun denies(sessionId: String, request: ConfirmationRequest) = "no deletes"
        }
        val (provider, sm) = scripted(use(ToolCall("c1", "sh", rm)), done)
        loop(provider, sm, sh, hooks, policy = { r -> policyAsked++; r.associate { it.callId to true } }, audit = audit)
            .turn(AgentSession(id = "s"), "go", config)

        sh.ran shouldBe emptyList()
        policyAsked shouldBe 0
        toolMessage(1) shouldContain "no deletes"
        audit.single().let { it.decision shouldBe "denied"; it.rule shouldBe "hook" }
    }

    test("BEFORE_PROVIDER_REQUEST can edit the request, and nested loops inherit the hooks") {
        val hooks = object : LoopHooks {
            override suspend fun beforeRequest(sessionId: String, request: CompletionRequest) = request.copy(systemPrompt = "edited")
        }
        val (provider, sm) = scripted(done)
        withContext(HookScope(hooks)) {
            AgentLoop(provider, ToolRegistry(), sm, contextWindowTokens = 100_000).turn(AgentSession(id = "s"), "go", config)
        }
        requests.single().systemPrompt shouldBe "edited"
    }
})
