package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

class SubagentToolTest : FunSpec({

    val explore = AgentDefinition(
        name = "explore",
        description = "Read-only search",
        systemPrompt = "You search code.",
        allowedTools = listOf("read_file")
    )
    val recursive = AgentDefinition(
        name = "recursive",
        description = "Can delegate further",
        systemPrompt = "You can delegate.",
        allowedTools = listOf("delegate_to_subagent")
    )

    val readTool = object : Tool {
        override val name = "read_file"
        override val description = "Reads a file"
        override val parametersJson = "{}"
        override suspend fun execute(argumentsJson: String) = "file contents"
    }

    fun writeTool() = object : Tool {
        override val name = "write_file"
        override val description = "Writes a file"
        override val parametersJson = "{}"
        override suspend fun execute(argumentsJson: String) = "written"
    }

    fun buildTool(
        definitions: List<AgentDefinition>,
        provider: LLMProvider,
        sessionsDir: Path,
        fullRegistry: ToolRegistry = ToolRegistry().register(readTool),
        depth: Int = 0
    ): SubagentTool = SubagentTool(
        definitions = definitions,
        provider = provider,
        fullRegistry = fullRegistry,
        sessionManager = FileSessionManager(sessionsDir),
        parentSessionId = "parent-1",
        parentConfig = AgentConfig(model = "parent-model"),
        contextWindowTokens = TEST_CONTEXT_WINDOW,
        depth = depth
    )

    test("name is delegate_to_subagent") {
        buildTool(listOf(explore), mockk(), createTempDirectory("subagent-test")).name shouldBe "delegate_to_subagent"
    }

    test("description lists available agent types") {
        val tool = buildTool(listOf(explore), mockk(), createTempDirectory("subagent-test"))
        tool.description shouldContain "explore"
        tool.description shouldContain "Read-only search"
    }

    test("execute() returns error for unknown subagent type without calling the LLM") {
        val provider = mockk<LLMProvider>()
        val tool = buildTool(listOf(explore), provider, createTempDirectory("subagent-test"))

        val result = runBlocking { tool.execute("""{"subagent_type":"ghost","prompt":"do something"}""") }

        result shouldContain "unknown subagent type"
        coVerify(exactly = 0) { provider.stream(any()) }
    }

    test("execute() returns error when max delegation depth is exceeded, without calling the LLM") {
        val provider = mockk<LLMProvider>()
        val tool = buildTool(listOf(explore), provider, createTempDirectory("subagent-test"), depth = 3)

        val result = runBlocking { tool.execute("""{"subagent_type":"explore","prompt":"go"}""") }

        result shouldContain "max delegation depth"
        coVerify(exactly = 0) { provider.stream(any()) }
    }

    test("execute() runs the nested loop and returns its final text") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns LLMResponse.Text("Found it in Auth.kt", TokenUsage(10, 5)).toStreamFlow()
        val tool = buildTool(listOf(explore), provider, createTempDirectory("subagent-test"))

        val result = runBlocking { tool.execute("""{"subagent_type":"explore","prompt":"find auth code"}""") }

        result shouldBe "Found it in Auth.kt"
    }

    test("execute() persists the subagent session tagged with the parent session id") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        val sessionsDir = createTempDirectory("subagent-test")
        val sessionManager = FileSessionManager(sessionsDir)
        val tool = SubagentTool(
            definitions = listOf(explore),
            provider = provider,
            fullRegistry = ToolRegistry().register(readTool),
            sessionManager = sessionManager,
            parentSessionId = "parent-42",
            parentConfig = AgentConfig(model = "parent-model"),
            contextWindowTokens = TEST_CONTEXT_WINDOW
        )

        runBlocking { tool.execute("""{"subagent_type":"explore","prompt":"find auth code"}""") }

        val metas = sessionManager.list()
        metas shouldHaveSize 1
        metas.first().parentSessionId shouldBe "parent-42"
    }

    test("execute() scopes the nested loop to only the definition's allowedTools") {
        val provider = mockk<LLMProvider>()
        val capturedRequests = mutableListOf<CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            LLMResponse.Text("ok", TokenUsage(1, 1)).toStreamFlow()
        }
        val fullRegistry = ToolRegistry().register(readTool).register(writeTool())
        val tool = buildTool(listOf(explore), provider, createTempDirectory("subagent-test"), fullRegistry = fullRegistry)

        runBlocking { tool.execute("""{"subagent_type":"explore","prompt":"go"}""") }

        capturedRequests.first().tools.map { it.name } shouldBe listOf("read_file")
    }

    test("execute() allows further delegation only when allowedTools includes the delegate tool itself") {
        val provider = mockk<LLMProvider>()
        val capturedRequests = mutableListOf<CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            LLMResponse.Text("ok", TokenUsage(1, 1)).toStreamFlow()
        }
        val tool = buildTool(listOf(recursive), provider, createTempDirectory("subagent-test"))

        runBlocking { tool.execute("""{"subagent_type":"recursive","prompt":"go"}""") }

        capturedRequests.first().tools.map { it.name } shouldBe listOf("delegate_to_subagent")
    }

    // The tests below mirror the real CLI wiring: SubagentTool is registered into the very
    // fullRegistry it holds a reference to (see SophiCli.kt), so `delegate_to_subagent` is a
    // genuine, pre-existing entry in fullRegistry — not merely absent as in the test above.
    // That means ToolRegistry.subset() actually copies an existing depth-0 SubagentTool entry
    // before SubagentTool.execute() overwrites it with a depth-incremented instance, and a real
    // nested AgentLoop.turn() drives a second, genuine level of recursion end-to-end.

    test("execute() runs a real two-level delegation through a self-referential fullRegistry") {
        val provider = mockk<LLMProvider>()
        val sessionsDir = createTempDirectory("subagent-test")
        val sessionManager = FileSessionManager(sessionsDir)
        val fullRegistry = ToolRegistry().register(readTool)

        val depth0Tool = SubagentTool(
            definitions = listOf(recursive),
            provider = provider,
            fullRegistry = fullRegistry,
            sessionManager = sessionManager,
            parentSessionId = "parent-1",
            parentConfig = AgentConfig(model = "parent-model"),
            contextWindowTokens = TEST_CONTEXT_WINDOW
        )
        // Self-referential registration, exactly as SophiCli.kt wires it in production.
        fullRegistry.register(depth0Tool)

        var callCount = 0
        every { provider.stream(any()) } answers {
            callCount++
            if (callCount == 1) {
                // depth-0's own nested loop decides to delegate one level further.
                LLMResponse.ToolUse(
                    calls = listOf(
                        ToolCall(
                            id = "call-1",
                            name = "delegate_to_subagent",
                            argumentsJson = """{"subagent_type":"recursive","prompt":"nested task"}"""
                        )
                    ),
                    usage = TokenUsage(5, 5)
                ).toStreamFlow()
            } else {
                // Every completion from here on (the depth-1 subagent's own turn, and the
                // depth-0 loop's follow-up turn once the tool result comes back) settles on
                // the same final text, so the value that survives to the top is unambiguously
                // the depth-1 subagent's answer.
                LLMResponse.Text("depth-1 subagent result", TokenUsage(5, 5)).toStreamFlow()
            }
        }

        val result = runBlocking { depth0Tool.execute("""{"subagent_type":"recursive","prompt":"go"}""") }

        result shouldBe "depth-1 subagent result"

        // Two distinct subagent sessions were really created — one per delegation level —
        // proving genuine two-level recursion rather than a single level that merely saw the
        // tool listed. SubagentTool intentionally threads the *original* parentSessionId
        // unchanged through every nested level (see the design plan), so both sessions are
        // tagged with "parent-1" rather than chaining to their immediate caller.
        val sessions = sessionManager.list()
        sessions shouldHaveSize 2
        sessions.count { it.parentSessionId == "parent-1" } shouldBe 2
    }

    test("execute() refuses delegation at the max depth boundary through a self-referential fullRegistry") {
        val provider = mockk<LLMProvider>()
        val capturedRequests = mutableListOf<CompletionRequest>()
        val sessionsDir = createTempDirectory("subagent-test")
        val sessionManager = FileSessionManager(sessionsDir)
        val fullRegistry = ToolRegistry().register(readTool)

        val depth0Tool = SubagentTool(
            definitions = listOf(recursive),
            provider = provider,
            fullRegistry = fullRegistry,
            sessionManager = sessionManager,
            parentSessionId = "parent-1",
            parentConfig = AgentConfig(model = "parent-model"),
            contextWindowTokens = TEST_CONTEXT_WINDOW,
            maxDelegationDepth = 1
        )
        fullRegistry.register(depth0Tool)

        var callCount = 0
        every { provider.stream(any()) } answers {
            callCount++
            capturedRequests.add(firstArg())
            if (callCount == 1) {
                LLMResponse.ToolUse(
                    calls = listOf(
                        ToolCall(
                            id = "call-1",
                            name = "delegate_to_subagent",
                            argumentsJson = """{"subagent_type":"recursive","prompt":"go deeper"}"""
                        )
                    ),
                    usage = TokenUsage(5, 5)
                ).toStreamFlow()
            } else {
                LLMResponse.Text("stopped", TokenUsage(1, 1)).toStreamFlow()
            }
        }

        val result = runBlocking { depth0Tool.execute("""{"subagent_type":"recursive","prompt":"go"}""") }

        result shouldBe "stopped"

        // The depth-1 SubagentTool (registered by overwrite into the scoped registry) must have
        // refused delegation itself, i.e. real recursion reached the boundary rather than the
        // outer loop just declining to call the LLM again.
        val toolResultMessage = capturedRequests[1].messages.last { it.toolName == "delegate_to_subagent" }
        toolResultMessage.content shouldContain "max delegation depth (1) exceeded"

        // The depth-1 attempt never created its own subagent session because it was refused
        // before reaching the session/loop setup — only the depth-0 session exists.
        sessionManager.list() shouldHaveSize 1
    }

    test("execute() threads confirmationPolicy into the nested AgentLoop, denying a DESTRUCTIVE tool") {
        var executed = false
        val destructiveTool = object : Tool {
            override val name = "danger"
            override val description = "risky"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String {
                executed = true
                return "should not run"
            }
        }
        val destructiveDef = AgentDefinition(
            name = "operator",
            description = "Can run destructive tools",
            systemPrompt = "You take action.",
            allowedTools = listOf("danger")
        )
        val provider = mockk<LLMProvider>()
        var callCount = 0
        every { provider.stream(any()) } answers {
            callCount++
            if (callCount == 1)
                LLMResponse.ToolUse(
                    calls = listOf(ToolCall("c1", "danger", "{}")),
                    usage = TokenUsage(1, 0)
                ).toStreamFlow()
            else
                LLMResponse.Text("acknowledged", TokenUsage(1, 1)).toStreamFlow()
        }
        val fullRegistry = ToolRegistry().register(destructiveTool)
        val tool = SubagentTool(
            definitions = listOf(destructiveDef),
            provider = provider,
            fullRegistry = fullRegistry,
            sessionManager = FileSessionManager(createTempDirectory("subagent-test")),
            parentSessionId = "parent-1",
            parentConfig = AgentConfig(model = "parent-model"),
            contextWindowTokens = TEST_CONTEXT_WINDOW,
            confirmationPolicy = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
        )

        runBlocking { tool.execute("""{"subagent_type":"operator","prompt":"do it"}""") }

        executed shouldBe false
    }

    test("riskLevel is SAFE when expected_tools is omitted") {
        val tool = buildTool(listOf(explore), mockk(), createTempDirectory("subagent-test"))
        tool.riskLevel("""{"subagent_type":"explore","prompt":"go"}""") shouldBe RiskLevel.SAFE
    }

    test("riskLevel reflects the worst tier among declared expected_tools") {
        val destructiveTool = object : Tool {
            override val name = "danger"
            override val description = "risky"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "ran"
        }
        val fullRegistry = ToolRegistry().register(readTool).register(destructiveTool)
        val tool = buildTool(listOf(explore), mockk(), createTempDirectory("subagent-test"), fullRegistry = fullRegistry)

        tool.riskLevel("""{"subagent_type":"explore","prompt":"go","expected_tools":["read_file"]}""") shouldBe RiskLevel.SAFE
        tool.riskLevel("""{"subagent_type":"explore","prompt":"go","expected_tools":["read_file","danger"]}""") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("execute() grants a declared SAFE expected_tool to the nested loop without re-confirming it") {
        val safeTool = object : Tool {
            override val name = "lookup"
            override val description = "harmless"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE
            override suspend fun execute(argumentsJson: String) = "ran it"
        }
        val def = AgentDefinition(
            name = "operator",
            description = "Can run safe tools",
            systemPrompt = "You take action.",
            allowedTools = listOf("lookup")
        )
        val provider = mockk<LLMProvider>()
        var callCount = 0
        every { provider.stream(any()) } answers {
            callCount++
            if (callCount == 1)
                LLMResponse.ToolUse(
                    calls = listOf(ToolCall("c1", "lookup", "{}")),
                    usage = TokenUsage(1, 0)
                ).toStreamFlow()
            else
                LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        }
        val fullRegistry = ToolRegistry().register(safeTool)
        val tool = SubagentTool(
            definitions = listOf(def),
            provider = provider,
            fullRegistry = fullRegistry,
            sessionManager = FileSessionManager(createTempDirectory("subagent-test")),
            parentSessionId = "parent-1",
            parentConfig = AgentConfig(model = "parent-model"),
            contextWindowTokens = TEST_CONTEXT_WINDOW,
            confirmationPolicy = ConfirmationPolicy { throw AssertionError("should not be consulted for a granted tool") }
        )

        val result = runBlocking {
            tool.execute("""{"subagent_type":"operator","prompt":"do it","expected_tools":["lookup"]}""")
        }

        result shouldBe "done"
    }

    test("execute() does not auto-grant a DESTRUCTIVE expected_tool — confirmation policy still gates it") {
        var executed = false
        val destructiveTool = object : Tool {
            override val name = "danger"
            override val description = "risky"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String {
                executed = true
                return "ran it"
            }
        }
        val destructiveDef = AgentDefinition(
            name = "operator",
            description = "Can run destructive tools",
            systemPrompt = "You take action.",
            allowedTools = listOf("danger")
        )
        val provider = mockk<LLMProvider>()
        var callCount = 0
        every { provider.stream(any()) } answers {
            callCount++
            if (callCount == 1)
                LLMResponse.ToolUse(
                    calls = listOf(ToolCall("c1", "danger", "{}")),
                    usage = TokenUsage(1, 0)
                ).toStreamFlow()
            else
                LLMResponse.Text("acknowledged", TokenUsage(1, 1)).toStreamFlow()
        }
        val fullRegistry = ToolRegistry().register(destructiveTool)
        val tool = SubagentTool(
            definitions = listOf(destructiveDef),
            provider = provider,
            fullRegistry = fullRegistry,
            sessionManager = FileSessionManager(createTempDirectory("subagent-test")),
            parentSessionId = "parent-1",
            parentConfig = AgentConfig(model = "parent-model"),
            contextWindowTokens = TEST_CONTEXT_WINDOW,
            confirmationPolicy = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
        )

        runBlocking {
            tool.execute("""{"subagent_type":"operator","prompt":"do it","expected_tools":["danger"]}""")
        }

        executed shouldBe false
    }
})
