package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flow

class AgentLoopTest : FunSpec({
    val provider = mockk<LLMProvider>()
    val sessionManager = mockk<SessionManager>()
    val registry = ToolRegistry()
    val config = AgentConfig(model = "test-model", systemPrompt = "You are helpful.")
    lateinit var loop: AgentLoop

    beforeTest {
        clearMocks(provider, sessionManager)
        loop = AgentLoop(provider, registry, sessionManager)
    }

    // ── Text path ──────────────────────────────────────────────────────────────

    test("turn() appends USER then ASSISTANT entries on text response") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns LLMResponse.Text("Hello!", TokenUsage(10, 5)).toStreamFlow()
        every { sessionManager.save(any()) } just Runs

        val result = loop.turn(session, "Hi", config)

        result.branch() shouldHaveSize 2
        result.branch()[0].role shouldBe EntryRole.USER
        result.branch()[0].content shouldBe "Hi"
        result.branch()[1].role shouldBe EntryRole.ASSISTANT
        result.branch()[1].content shouldBe "Hello!"
    }

    test("turn() calls sessionManager.save() exactly once on text response") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns LLMResponse.Text("OK", TokenUsage(1, 1)).toStreamFlow()
        every { sessionManager.save(any()) } just Runs

        loop.turn(session, "test", config)

        verify(exactly = 1) { sessionManager.save(any()) }
    }

    test("turn() includes session history in the CompletionRequest messages") {
        val session = AgentSession(id = "s1")
        session.append(EntryRole.USER, "previous question")
        session.append(EntryRole.ASSISTANT, "previous answer")

        var capturedRequest: dev.sophi.ai.api.CompletionRequest? = null
        every { provider.stream(any()) } answers {
            capturedRequest = firstArg()
            LLMResponse.Text("response", TokenUsage(1, 1)).toStreamFlow()
        }
        every { sessionManager.save(any()) } just Runs

        loop.turn(session, "new question", config)

        // 2 history + 1 new user = 3 messages
        capturedRequest!!.messages shouldHaveSize 3
        capturedRequest!!.messages[2].content shouldBe "new question"
    }

    test("turn() passes model and systemPrompt from AgentConfig to CompletionRequest") {
        val session = AgentSession(id = "s1")
        var capturedRequest: dev.sophi.ai.api.CompletionRequest? = null
        every { provider.stream(any()) } answers {
            capturedRequest = firstArg()
            LLMResponse.Text("ok", TokenUsage(1, 1)).toStreamFlow()
        }
        every { sessionManager.save(any()) } just Runs

        loop.turn(session, "test", config)

        capturedRequest!!.model shouldBe "test-model"
        capturedRequest!!.systemPrompt shouldBe "You are helpful."
    }

    test("turn() throws IllegalStateException when provider.stream() fails") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flow<StreamEvent> { throw IllegalStateException("provider down") }

        shouldThrow<IllegalStateException> { loop.turn(session, "test", config) }
    }

    test("turn() does NOT modify session on error") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flow<StreamEvent> { throw IllegalStateException("boom") }

        runCatching { loop.turn(session, "test", config) }

        session.entries.shouldHaveSize(0)  // session unchanged
    }

    // ── Tool dispatch ──────────────────────────────────────────────────────────

    test("turn() dispatches a single tool call and loops to get text response") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "calculator"
            override val description = "Calculates"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "42"
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager)

        every { provider.stream(any()) } returnsMany listOf(
            LLMResponse.ToolUse(
                calls = listOf(dev.sophi.ai.api.ToolCall("call-1", "calculator", "{}")),
                usage = TokenUsage(10, 0)
            ).toStreamFlow(),
            LLMResponse.Text("The answer is 42", TokenUsage(5, 8)).toStreamFlow()
        )
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "What is 6 times 7?", config)

        // USER, ASSISTANT (tool-call round, replay=false), TOOL_RESULT (replay=false), final ASSISTANT
        result.branch() shouldHaveSize 4
        result.branch().last().content shouldBe "The answer is 42"
        coVerify(exactly = 2) { provider.stream(any()) }
    }

    test("turn() includes tool call and result in subsequent CompletionRequest messages") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "ping"
            override val description = "Pings"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "pong"
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager)

        val capturedRequests = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            if (capturedRequests.size == 1)
                LLMResponse.ToolUse(
                    calls = listOf(dev.sophi.ai.api.ToolCall("c1", "ping", "{}")),
                    usage = TokenUsage(1, 0)
                ).toStreamFlow()
            else
                LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        }
        every { sessionManager.save(any()) } just Runs

        loopWithTool.turn(session, "ping", config)

        // Second request: original user msg + assistant tool-call msg + tool result msg
        capturedRequests[1].messages shouldHaveSize 3
        capturedRequests[1].messages[1].toolCalls!! shouldHaveSize 1  // assistant's tool call
        capturedRequests[1].messages[2].role shouldBe dev.sophi.ai.api.MessageRole.TOOL
        capturedRequests[1].messages[2].content shouldBe "pong"
        capturedRequests[1].messages[2].toolCallId shouldBe "c1"
        capturedRequests[1].messages[2].toolName shouldBe "ping"
    }

    test("turn() returns error string for unknown tool name") {
        val session = AgentSession(id = "s1")
        val emptyRegistry = ToolRegistry()
        val loopNoTools = AgentLoop(provider, emptyRegistry, sessionManager)

        val capturedRequests = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            if (capturedRequests.size == 1)
                LLMResponse.ToolUse(
                    calls = listOf(dev.sophi.ai.api.ToolCall("c1", "nonexistent", "{}")),
                    usage = TokenUsage(1, 0)
                ).toStreamFlow()
            else
                LLMResponse.Text("fallback", TokenUsage(1, 1)).toStreamFlow()
        }
        every { sessionManager.save(any()) } just Runs

        loopNoTools.turn(session, "test", config)

        val toolResultMsg = capturedRequests[1].messages.last()
        toolResultMsg.content shouldBe "Error: Tool 'nonexistent' not found"
    }

    test("turn() wraps tool execute exception as error string") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "broken"
            override val description = "Always throws"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String =
                throw RuntimeException("disk full")
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager)

        val capturedRequests = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            if (capturedRequests.size == 1)
                LLMResponse.ToolUse(
                    calls = listOf(dev.sophi.ai.api.ToolCall("c1", "broken", "{}")),
                    usage = TokenUsage(1, 0)
                ).toStreamFlow()
            else
                LLMResponse.Text("recovered", TokenUsage(1, 1)).toStreamFlow()
        }
        every { sessionManager.save(any()) } just Runs

        loopWithTool.turn(session, "test", config)

        val toolResultMsg = capturedRequests[1].messages.last()
        toolResultMsg.content shouldBe "Error: disk full"
    }

    test("turn() throws IllegalStateException when maxToolRounds exceeded and the loop guard always continues") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "loop"
            override val description = "Always loops"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "still looping"
        })
        // ALWAYS_CONTINUE: the loop guard's own "approaching the round budget" trigger would
        // otherwise stop this early under the default NEVER_CONTINUE policy (see the dedicated
        // loop-guard tests below) — this test is specifically about the hard ceiling underneath it.
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)
        val tightConfig = config.copy(maxToolRounds = 2)

        every { provider.stream(any()) } returns LLMResponse.ToolUse(
            calls = listOf(dev.sophi.ai.api.ToolCall("c1", "loop", "{}")),
            usage = TokenUsage(1, 0)
        ).toStreamFlow()

        shouldThrow<IllegalStateException> { loopWithTool.turn(session, "go", tightConfig) }
    }

    // ── Loop guard ──────────────────────────────────────────────────────────

    test("turn() stops early after 3 consecutive fully-failed rounds under the default (never-continue) guard") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "broken"
            override val description = "Always fails"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String = throw RuntimeException("nope")
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager)

        every { provider.stream(any()) } returns LLMResponse.ToolUse(
            calls = listOf(dev.sophi.ai.api.ToolCall("c1", "broken", "{}")),
            usage = TokenUsage(1, 0)
        ).toStreamFlow()
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "go", config.copy(maxToolRounds = 20))

        result.branch().last().content shouldContain "Stopped early"
        result.branch().last().content shouldContain "3 consecutive"
        coVerify(exactly = 3) { provider.stream(any()) }
    }

    test("turn() keeps going past 3 consecutive failures when the loop guard says yes") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        var attempt = 0
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "flaky"
            override val description = "Fails 3 times then succeeds"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String {
                attempt++
                if (attempt <= 3) throw RuntimeException("nope")
                return "finally"
            }
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)

        every { provider.stream(any()) } returnsMany listOf(
            LLMResponse.ToolUse(calls = listOf(dev.sophi.ai.api.ToolCall("c1", "flaky", "{}")), usage = TokenUsage(1, 0)).toStreamFlow(),
            LLMResponse.ToolUse(calls = listOf(dev.sophi.ai.api.ToolCall("c2", "flaky", "{}")), usage = TokenUsage(1, 0)).toStreamFlow(),
            LLMResponse.ToolUse(calls = listOf(dev.sophi.ai.api.ToolCall("c3", "flaky", "{}")), usage = TokenUsage(1, 0)).toStreamFlow(),
            LLMResponse.ToolUse(calls = listOf(dev.sophi.ai.api.ToolCall("c4", "flaky", "{}")), usage = TokenUsage(1, 0)).toStreamFlow(),
            LLMResponse.Text("recovered", TokenUsage(1, 1)).toStreamFlow()
        )
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "go", config.copy(maxToolRounds = 20))

        result.branch().last().content shouldBe "recovered"
    }

    test("turn() stops early when a glob/grep search broadens beyond an earlier scoped path") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "glob"
            override val description = "Finds files"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "no matches"
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager)

        every { provider.stream(any()) } returnsMany listOf(
            LLMResponse.ToolUse(
                calls = listOf(dev.sophi.ai.api.ToolCall("c1", "glob", """{"path":"transcribe","pattern":"*.txt"}""")),
                usage = TokenUsage(1, 0)
            ).toStreamFlow(),
            LLMResponse.ToolUse(
                calls = listOf(dev.sophi.ai.api.ToolCall("c2", "glob", """{"pattern":"**/*transcribe*"}""")),
                usage = TokenUsage(1, 0)
            ).toStreamFlow()
        )
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "go", config.copy(maxToolRounds = 20))

        result.branch().last().content shouldContain "Stopped early"
        result.branch().last().content shouldContain "broadened"
        coVerify(exactly = 2) { provider.stream(any()) }
    }

    test("turn() stops early when approaching the tool-round budget under the default guard") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "ok"
            override val description = "Always succeeds"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "fine"
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager)

        every { provider.stream(any()) } returns LLMResponse.ToolUse(
            calls = listOf(dev.sophi.ai.api.ToolCall("c1", "ok", "{}")),
            usage = TokenUsage(1, 0)
        ).toStreamFlow()
        every { sessionManager.save(any()) } just Runs

        // margin is 3: with maxToolRounds=4, round 1 (4-3=1) already crosses it
        val result = loopWithTool.turn(session, "go", config.copy(maxToolRounds = 4))

        result.branch().last().content shouldContain "Stopped early"
        result.branch().last().content shouldContain "round"
        coVerify(exactly = 1) { provider.stream(any()) }
    }

    // ── Turn events ──────────────────────────────────────────────────────────

    test("turn() emits a single Token event with the final text when there are no tool calls") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns LLMResponse.Text("Hello!", TokenUsage(10, 5)).toStreamFlow()
        every { sessionManager.save(any()) } just Runs

        val events = mutableListOf<TurnEvent>()
        loop.turn(session, "Hi", config) { events.add(it) }

        events shouldBe listOf(TurnEvent.Token("Hello!"))
    }

    test("turn() emits ToolCallStarted then ToolCallFinished around a tool call, then a final Token") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "calculator"
            override val description = "Calculates"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "42"
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager)
        every { provider.stream(any()) } returnsMany listOf(
            LLMResponse.ToolUse(
                calls = listOf(dev.sophi.ai.api.ToolCall("call-1", "calculator", "{}")),
                usage = TokenUsage(10, 0)
            ).toStreamFlow(),
            LLMResponse.Text("The answer is 42", TokenUsage(5, 8)).toStreamFlow()
        )
        every { sessionManager.save(any()) } just Runs

        val events = mutableListOf<TurnEvent>()
        loopWithTool.turn(session, "What is 6 times 7?", config) { events.add(it) }

        events shouldBe listOf(
            TurnEvent.ToolCallStarted("calculator", "{}"),
            TurnEvent.ToolCallFinished("calculator", "42"),
            TurnEvent.Token("The answer is 42")
        )
    }

    test("turn() emits ToolCallFinished with the error string when a tool throws") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "broken"
            override val description = "Always throws"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String =
                throw RuntimeException("disk full")
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager)
        every { provider.stream(any()) } returnsMany listOf(
            LLMResponse.ToolUse(
                calls = listOf(dev.sophi.ai.api.ToolCall("c1", "broken", "{}")),
                usage = TokenUsage(1, 0)
            ).toStreamFlow(),
            LLMResponse.Text("recovered", TokenUsage(1, 1)).toStreamFlow()
        )
        every { sessionManager.save(any()) } just Runs

        val events = mutableListOf<TurnEvent>()
        loopWithTool.turn(session, "test", config) { events.add(it) }

        events shouldBe listOf(
            TurnEvent.ToolCallStarted("broken", "{}"),
            TurnEvent.ToolCallFinished("broken", "Error: disk full", isError = true),
            TurnEvent.Token("recovered")
        )
    }

    // ── Confirmation policy ──────────────────────────────────────────────────

    test("turn() does not consult confirmationPolicy for a SAFE tool call") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "safe-tool"
            override val description = "Safe"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "ok"
        })
        val policy = dev.sophi.core.tools.ConfirmationPolicy { _, _ ->
            throw AssertionError("should not be called for a SAFE tool")
        }
        val loopWithPolicy = AgentLoop(provider, toolRegistry, sessionManager, confirmationPolicy = policy)
        every { provider.stream(any()) } returnsMany listOf(
            LLMResponse.ToolUse(
                calls = listOf(dev.sophi.ai.api.ToolCall("c1", "safe-tool", "{}")),
                usage = TokenUsage(1, 0)
            ).toStreamFlow(),
            LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        )
        every { sessionManager.save(any()) } just Runs

        val result = loopWithPolicy.turn(session, "go", config)

        result.branch().last().content shouldBe "done"
    }

    test("turn() denies a DESTRUCTIVE tool call when the policy returns false, without executing it") {
        val session = AgentSession(id = "s1")
        var executed = false
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "danger"
            override val description = "Risky"
            override val parametersJson = "{}"
            override val riskLevel = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String {
                executed = true
                return "should not run"
            }
        })
        val loopWithPolicy = AgentLoop(
            provider, toolRegistry, sessionManager,
            confirmationPolicy = dev.sophi.core.tools.ConfirmationPolicy { _, _ -> false }
        )
        val capturedRequests = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            if (capturedRequests.size == 1)
                LLMResponse.ToolUse(
                    calls = listOf(dev.sophi.ai.api.ToolCall("c1", "danger", "{}")),
                    usage = TokenUsage(1, 0)
                ).toStreamFlow()
            else
                LLMResponse.Text("acknowledged", TokenUsage(1, 1)).toStreamFlow()
        }
        every { sessionManager.save(any()) } just Runs

        loopWithPolicy.turn(session, "go", config)

        executed shouldBe false
        capturedRequests[1].messages.last().content shouldBe
            "Error: Tool 'danger' execution denied by confirmation policy"
    }

    test("turn() executes a DESTRUCTIVE tool call when the policy returns true") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "danger"
            override val description = "Risky"
            override val parametersJson = "{}"
            override val riskLevel = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "did the risky thing"
        })
        val loopWithPolicy = AgentLoop(
            provider, toolRegistry, sessionManager,
            confirmationPolicy = dev.sophi.core.tools.ConfirmationPolicy { _, _ -> true }
        )
        every { provider.stream(any()) } returnsMany listOf(
            LLMResponse.ToolUse(
                calls = listOf(dev.sophi.ai.api.ToolCall("c1", "danger", "{}")),
                usage = TokenUsage(1, 0)
            ).toStreamFlow(),
            LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        )
        every { sessionManager.save(any()) } just Runs

        val result = loopWithPolicy.turn(session, "go", config)

        result.branch().last().content shouldBe "done"
    }

    test("turn() resolves all confirmations before executing any tool in the batch") {
        val session = AgentSession(id = "s1")
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "d1"
            override val description = "Risky 1"
            override val parametersJson = "{}"
            override val riskLevel = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String { log.add("exec:d1"); return "1" }
        })
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "d2"
            override val description = "Risky 2"
            override val parametersJson = "{}"
            override val riskLevel = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String { log.add("exec:d2"); return "2" }
        })
        val policy = dev.sophi.core.tools.ConfirmationPolicy { toolName, _ -> log.add("confirm:$toolName"); true }
        val loopWithPolicy = AgentLoop(provider, toolRegistry, sessionManager, confirmationPolicy = policy)
        every { provider.stream(any()) } returnsMany listOf(
            LLMResponse.ToolUse(
                calls = listOf(
                    dev.sophi.ai.api.ToolCall("c1", "d1", "{}"),
                    dev.sophi.ai.api.ToolCall("c2", "d2", "{}")
                ),
                usage = TokenUsage(1, 0)
            ).toStreamFlow(),
            LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        )
        every { sessionManager.save(any()) } just Runs

        loopWithPolicy.turn(session, "go", config)

        log.take(2) shouldBe listOf("confirm:d1", "confirm:d2")
    }
})
