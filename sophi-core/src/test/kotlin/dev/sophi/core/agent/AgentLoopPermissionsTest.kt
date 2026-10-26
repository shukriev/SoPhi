package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.audit.AuditSink
import dev.sophi.core.permissions.Effect
import dev.sophi.core.permissions.PermissionRule
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.AutoModeConfirmationPolicy
import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskClassifier
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.withContext
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText

class AgentLoopPermissionsTest : FunSpec({
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
        private val risk: RiskLevel = RiskLevel.SAFE,
        private val run: suspend (String) -> String = { "ok" },
    ) : Tool {
        var executed = 0
        override val description = "fake"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = risk
        override suspend fun execute(argumentsJson: String): String { executed++; return run(argumentsJson) }
    }

    fun rules(vararg r: PermissionRule) = PermissionRules(r.toList(), emptyList(), null, null, emptySet())
    fun deny(tool: String, pattern: String? = null) = PermissionRule(tool, pattern, Effect.DENY)

    fun loop(
        provider: LLMProvider, sm: SessionManager, vararg tools: Tool,
        policy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
        grants: Set<String> = emptySet(),
        audit: AuditSink? = null,
        permissions: PermissionRules? = null,
        registry: ToolRegistry = ToolRegistry().apply { tools.forEach { register(it) } },
    ) = AgentLoop(provider, registry, sm,
        confirmationPolicy = policy, grants = grants, contextWindowTokens = 100_000, audit = audit, permissions = permissions)

    fun bash(command: String) = ToolCall("b", "bash", """{"command":${kotlinx.serialization.json.JsonPrimitive(command)}}""")

    test("a deny rule blocks a call under an allow-all policy") {
        val entries = mutableListOf<AuditEntry>()
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val (provider, sm) = scripted(use(ToolCall("c1", "rm", "{}")), done)
        loop(provider, sm, rm, audit = { entries += it }, permissions = rules(deny("rm")))
            .turn(AgentSession(id = "s"), "go", config)

        rm.executed shouldBe 0
        requests[1].messages.last { it.role == MessageRole.TOOL }.content shouldContain "denied by a permission rule"
        entries.single().rule shouldBe "rule:deny"
        entries.single().outcome shouldBe "denied"
    }

    test("a deny rule blocks SAFE and granted tools") {
        val look = FakeTool("look")
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val (provider, sm) = scripted(use(ToolCall("c1", "look", "{}"), ToolCall("c2", "rm", "{}")), done)
        loop(provider, sm, look, rm, policy = ConfirmationPolicy.DENY_ALL, grants = setOf("rm"),
            permissions = rules(deny("look"), deny("rm")))
            .turn(AgentSession(id = "s"), "go", config)

        look.executed shouldBe 0
        rm.executed shouldBe 0
    }

    test("an allow rule skips a DENY_ALL policy") {
        val entries = mutableListOf<AuditEntry>()
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val policy = spyk(ConfirmationPolicy.DENY_ALL)
        val (provider, sm) = scripted(use(ToolCall("c1", "rm", "{}")), done)
        loop(provider, sm, rm, policy = policy, audit = { entries += it },
            permissions = rules(PermissionRule("rm", null, Effect.ALLOW)))
            .turn(AgentSession(id = "s"), "go", config)

        rm.executed shouldBe 1
        coVerify(exactly = 0) { policy.confirm(any()) }
        entries.single().rule shouldBe "rule:allow"
    }

    test("an ask rule sends a SAFE call to the policy") {
        val entries = mutableListOf<AuditEntry>()
        val seen = mutableListOf<String>()
        val look = FakeTool("look")
        val policy = ConfirmationPolicy { reqs -> seen += reqs.map { it.toolName }; ConfirmationPolicy.DENY_ALL.confirm(reqs) }
        val (provider, sm) = scripted(use(ToolCall("c1", "look", "{}")), done)
        loop(provider, sm, look, policy = policy, audit = { entries += it },
            permissions = rules(PermissionRule("look", null, Effect.ASK)))
            .turn(AgentSession(id = "s"), "go", config)

        seen shouldBe listOf("look")
        look.executed shouldBe 0
        entries.single().rule shouldBe "deny-all"
    }

    fun godMode(registry: ToolRegistry) =
        AutoModeConfirmationPolicy(registry, RiskClassifier.ALWAYS_LOW_RISK, ConfirmationPolicy.DENY_ALL, "allow-all")

    test("control: god mode runs rm without a rule") {
        val root = createTempDirectory("perm-god")
        val marker = root.resolve("marker").also { it.writeText("x") }
        val registry = ToolRegistry().register(BashTool(root))
        val (provider, sm) = scripted(use(bash("rm marker")), done)
        loop(provider, sm, policy = godMode(registry), registry = registry).turn(AgentSession(id = "s"), "go", config)

        marker.exists() shouldBe false
    }

    test("a deny rule beats god mode") {
        val root = createTempDirectory("perm-god")
        val marker = root.resolve("marker").also { it.writeText("x") }
        val registry = ToolRegistry().register(BashTool(root))
        val (provider, sm) = scripted(use(bash("rm marker")), use(bash("ls")), done)
        loop(provider, sm, policy = godMode(registry), registry = registry, permissions = rules(deny("bash", "rm *")))
            .turn(AgentSession(id = "s"), "go", config)

        marker.exists() shouldBe true
        requests[1].messages.last { it.role == MessageRole.TOOL }.content shouldContain "denied by a permission rule"
        requests[2].messages.last { it.role == MessageRole.TOOL }.content shouldContain "marker"
    }

    test("under god mode an unsplittable bash command with a deny rule never runs") {
        val root = createTempDirectory("perm-god")
        val marker = root.resolve("x").also { it.writeText("x") }
        val registry = ToolRegistry().register(BashTool(root))
        // The brief's heredoc, then an rm that would run after it: the splitter gives up on `<<`, so R3 denies the whole command.
        val (provider, sm) = scripted(use(bash("cat <<EOF\nrm x\nEOF\nrm x")), done)
        loop(provider, sm, policy = godMode(registry), registry = registry, permissions = rules(deny("bash", "rm *")))
            .turn(AgentSession(id = "s"), "go", config)

        marker.exists() shouldBe true
        requests[1].messages.last { it.role == MessageRole.TOOL }.content shouldContain "denied by a permission rule"
    }

    test("a nested subagent loop inherits the rules") {
        val rm = FakeTool("rm", RiskLevel.DESTRUCTIVE)
        val (provider, sm) = scripted(
            use(ToolCall("d1", "delegate_to_subagent", """{"subagent_type":"worker","prompt":"clean up"}""")),
            use(ToolCall("c1", "rm", "{}")), done, done,
        )
        val worker = AgentDefinition("worker", "does work", "You work.", allowedTools = listOf("rm"))
        val subagent = SubagentTool(listOf(worker), provider, ToolRegistry().register(rm),
            FileSessionManager(createTempDirectory("perm-sub")), config, 100_000)
        withContext(SessionIdContext("p")) {
            loop(provider, sm, subagent, permissions = rules(deny("rm"))).turn(AgentSession(id = "p"), "go", config)
        }

        rm.executed shouldBe 0
    }

    test("requests carry alwaysAllow when it can be offered") {
        val seen = mutableListOf<ConfirmationRequest>()
        val policy = ConfirmationPolicy { reqs -> seen += reqs; reqs.associate { it.callId to false } }
        val root = createTempDirectory("perm-always")
        val (provider, sm) = scripted(
            use(ToolCall("a", "bash", """{"command":"touch *.kt"}"""), ToolCall("b", "bash", """{"command":"a > b"}""")), done)
        loop(provider, sm, BashTool(root), policy = policy, permissions = rules(deny("bash", "rm *")))
            .turn(AgentSession(id = "s"), "go", config)

        val byId = seen.associateBy { it.callId }
        byId.getValue("a").alwaysAllow shouldBe listOf(PermissionRule("bash", "touch \\*.kt", Effect.ALLOW))
        byId.getValue("b").alwaysAllow shouldBe null
    }

    test("an ask-matched request is marked askRule and never offers always") {
        val seen = mutableListOf<ConfirmationRequest>()
        val policy = ConfirmationPolicy { reqs -> seen += reqs; reqs.associate { it.callId to false } }
        val (provider, sm) = scripted(use(ToolCall("a", "bash", """{"command":"ls *.kt"}""")), done)
        // the written allow could never apply: ask beats allow
        loop(provider, sm, BashTool(createTempDirectory("perm-ask")), policy = policy,
            permissions = rules(PermissionRule("bash", "ls *", Effect.ASK)))
            .turn(AgentSession(id = "s"), "go", config)

        seen.single().askRule shouldBe true
        seen.single().alwaysAllow shouldBe null
    }

    test("an ask rule reaches a person under god mode") {
        val root = createTempDirectory("perm-god")
        val marker = root.resolve("marker").also { it.writeText("x") }
        val registry = ToolRegistry().register(BashTool(root))
        val asked = mutableListOf<String>()
        val human = ConfirmationPolicy { reqs -> asked += reqs.map { it.toolName }; reqs.associate { it.callId to false } }
        val (provider, sm) = scripted(use(bash("rm marker")), done)
        loop(provider, sm, policy = AutoModeConfirmationPolicy(registry, RiskClassifier.ALWAYS_LOW_RISK, human, "allow-all"),
            registry = registry, permissions = rules(PermissionRule("bash", "rm *", Effect.ASK)))
            .turn(AgentSession(id = "s"), "go", config)

        asked shouldBe listOf("bash")
        marker.exists() shouldBe true
    }
})
