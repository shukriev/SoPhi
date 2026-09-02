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
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flow

/** A window large enough that no existing test's tiny TokenUsage values ever trip compaction. */
private const val TEST_CONTEXT_WINDOW = 100_000

class AgentLoopTest : FunSpec({
    val provider = mockk<LLMProvider>()
    val sessionManager = mockk<SessionManager>()
    val registry = ToolRegistry()
    val config = AgentConfig(model = "test-model", systemPrompt = "You are helpful.")
    lateinit var loop: AgentLoop

    fun newLoop(
        toolRegistry: ToolRegistry = registry,
        confirmationPolicy: dev.sophi.core.tools.ConfirmationPolicy =
            dev.sophi.core.tools.ConfirmationPolicy.ALLOW_ALL,
        grants: Set<String> = emptySet(),
        loopGuard: LoopGuardPolicy = LoopGuardPolicy.NEVER_CONTINUE,
        contextWindowTokens: Int = TEST_CONTEXT_WINDOW
    ): AgentLoop = AgentLoop(
        provider, toolRegistry, sessionManager,
        confirmationPolicy = confirmationPolicy,
        grants = grants,
        loopGuard = loopGuard,
        contextWindowTokens = contextWindowTokens
    )

    beforeTest {
        clearMocks(provider, sessionManager)
        loop = newLoop()
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

    test("turn() consumes a StreamEvent.Usage without surfacing it as a TurnEvent") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flow {
            emit(StreamEvent.Content("Hello!"))
            emit(StreamEvent.Usage(TokenUsage(inputTokens = 12, outputTokens = 3)))
        }
        every { sessionManager.save(any()) } just Runs

        val events = mutableListOf<TurnEvent>()
        val result = loop.turn(session, "Hi", config) { events.add(it) }

        events shouldBe listOf(TurnEvent.Token("Hello!"))
        result.branch().last().content shouldBe "Hello!"
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
        val loopWithTool = newLoop(toolRegistry)

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
        val loopWithTool = newLoop(toolRegistry)

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
        val loopNoTools = newLoop(emptyRegistry)

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
        val loopWithTool = newLoop(toolRegistry)

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

    test("turn() truncates an oversized tool result before it enters the conversation") {
        // Reproduces the "LLM stream error: Stream failed" bug: an MCP tool we don't control
        // (e.g. a browser snapshot) can return an arbitrarily large result. Built-in tools like
        // BashTool/FetchUrlTool cap their own output, but MCP-provided tools don't — so AgentLoop
        // must cap every tool result at the one point they all pass through, or a single huge
        // result can blow straight past the model's real context window before compaction ever
        // gets a chance to run (compaction only reacts to the *previous* round's usage).
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        val hugeResult = "x".repeat(500_000)
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "snapshot"
            override val description = "Returns a huge snapshot"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = hugeResult
        })
        val loopWithTool = newLoop(toolRegistry)

        val capturedRequests = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            if (capturedRequests.size == 1)
                LLMResponse.ToolUse(
                    calls = listOf(dev.sophi.ai.api.ToolCall("c1", "snapshot", "{}")),
                    usage = TokenUsage(1, 0)
                ).toStreamFlow()
            else
                LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        }
        every { sessionManager.save(any()) } just Runs

        loopWithTool.turn(session, "snapshot the page", config)

        val toolResultMsg = capturedRequests[1].messages.last()
        toolResultMsg.content.length shouldBeLessThan hugeResult.length
        toolResultMsg.content shouldContain "output truncated"
    }

    test("turn() stops gracefully at the maxToolRounds ceiling, persisting the rounds done so far") {
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
        // loop-guard tests below) — this test is specifically about the ceiling underneath it.
        val loopWithTool = newLoop(toolRegistry, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)
        val tightConfig = config.copy(maxToolRounds = 2)

        every { provider.stream(any()) } returns LLMResponse.ToolUse(
            calls = listOf(dev.sophi.ai.api.ToolCall("c1", "loop", "{}")),
            usage = TokenUsage(1, 0)
        ).toStreamFlow()
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "go", tightConfig)

        result.branch().last().content shouldContain "Stopped early"
        result.branch().last().content shouldContain "tool-round sanity ceiling (2)"
        result.tip?.metadata?.get("stopReason") shouldBe TurnStopReason.ToolRoundCeiling.name
    }

    test("turn() saves the rounds accumulated before the maxToolRounds ceiling instead of discarding them") {
        // Regression test for the data-loss bug: the old code threw here, and session state was
        // only ever appended on the natural-completion and loop-guard-stop paths.
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "loop"
            override val description = "Always loops"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "still looping"
        })
        val loopWithTool = newLoop(toolRegistry, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)

        every { provider.stream(any()) } returns LLMResponse.ToolUse(
            calls = listOf(dev.sophi.ai.api.ToolCall("c1", "loop", "{}")),
            usage = TokenUsage(1, 0)
        ).toStreamFlow()
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "go", config.copy(maxToolRounds = 2))

        // USER, (ASSISTANT tool-call + TOOL_RESULT) x 2 rounds, final ASSISTANT stop message
        result.branch() shouldHaveSize 6
        result.branch().map { it.role } shouldBe listOf(
            EntryRole.USER,
            EntryRole.ASSISTANT, EntryRole.TOOL_RESULT,
            EntryRole.ASSISTANT, EntryRole.TOOL_RESULT,
            EntryRole.ASSISTANT
        )
        verify(exactly = 1) { sessionManager.save(any()) }
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
        val loopWithTool = newLoop(toolRegistry)

        every { provider.stream(any()) } returns LLMResponse.ToolUse(
            calls = listOf(dev.sophi.ai.api.ToolCall("c1", "broken", "{}")),
            usage = TokenUsage(1, 0)
        ).toStreamFlow()
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "go", config.copy(maxToolRounds = 20))

        result.branch().last().content shouldContain "Stopped early"
        result.branch().last().content shouldContain "3 consecutive"
        result.tip?.metadata?.get("stopReason") shouldBe TurnStopReason.LoopGuard.name
        coVerify(exactly = 3) { provider.stream(any()) }
    }

    test("turn() stops early after 3 consecutive rounds where the tool returns an 'Error: ' string instead of throwing") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "validating"
            override val description = "Returns a validation error instead of throwing"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String = "Error: 'start' and 'end' are required unless all_day=true"
        })
        val loopWithTool = newLoop(toolRegistry)

        every { provider.stream(any()) } returns LLMResponse.ToolUse(
            calls = listOf(dev.sophi.ai.api.ToolCall("c1", "validating", "{}")),
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
        val loopWithTool = newLoop(toolRegistry, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)

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
        val loopWithTool = newLoop(toolRegistry)

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
        val loopWithTool = newLoop(toolRegistry)

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
        val loopWithTool = newLoop(toolRegistry)
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
        val loopWithTool = newLoop(toolRegistry)
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
        val policy = dev.sophi.core.tools.ConfirmationPolicy { _ ->
            throw AssertionError("should not be called for a SAFE tool")
        }
        val loopWithPolicy = newLoop(toolRegistry, confirmationPolicy = policy)
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
            override fun riskLevel(argumentsJson: String) = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String {
                executed = true
                return "should not run"
            }
        })
        val loopWithPolicy = newLoop(
            toolRegistry,
            confirmationPolicy = dev.sophi.core.tools.ConfirmationPolicy { requests ->
                requests.associate { it.callId to false }
            }
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
            override fun riskLevel(argumentsJson: String) = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "did the risky thing"
        })
        val loopWithPolicy = newLoop(
            toolRegistry,
            confirmationPolicy = dev.sophi.core.tools.ConfirmationPolicy { requests ->
                requests.associate { it.callId to true }
            }
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

    test("turn() resolves all confirmations for a round in a single confirmationPolicy.confirm() call") {
        val session = AgentSession(id = "s1")
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "d1"
            override val description = "Risky 1"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String { log.add("exec:d1"); return "1" }
        })
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "d2"
            override val description = "Risky 2"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String { log.add("exec:d2"); return "2" }
        })
        var confirmCallCount = 0
        val policy = dev.sophi.core.tools.ConfirmationPolicy { requests ->
            confirmCallCount++
            requests.forEach { log.add("confirm:${it.toolName}") }
            requests.associate { it.callId to true }
        }
        val loopWithPolicy = newLoop(toolRegistry, confirmationPolicy = policy)
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

        confirmCallCount shouldBe 1
        log.filter { it.startsWith("confirm:") }.toSet() shouldBe setOf("confirm:d1", "confirm:d2")
    }

    test("turn() lets a granted tool run without consulting confirmationPolicy") {
        val session = AgentSession(id = "s1")
        var executed = false
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "danger"
            override val description = "Risky"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = dev.sophi.core.tools.RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String): String { executed = true; return "ran" }
        })
        val policy = dev.sophi.core.tools.ConfirmationPolicy {
            throw AssertionError("should not be called for a granted tool")
        }
        val loopWithGrant = newLoop(toolRegistry, confirmationPolicy = policy, grants = setOf("danger"))
        every { provider.stream(any()) } returnsMany listOf(
            LLMResponse.ToolUse(
                calls = listOf(dev.sophi.ai.api.ToolCall("c1", "danger", "{}")),
                usage = TokenUsage(1, 0)
            ).toStreamFlow(),
            LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
        )
        every { sessionManager.save(any()) } just Runs

        loopWithGrant.turn(session, "go", config)

        executed shouldBe true
    }

    // ── Mid-loop compaction ─────────────────────────────────────────────────

    /** A tool that always succeeds, so the loop guard never fires during compaction tests. */
    fun loopingTool() = object : dev.sophi.core.tools.Tool {
        override val name = "ok"
        override val description = "Always succeeds"
        override val parametersJson = "{}"
        override suspend fun execute(argumentsJson: String) = "fine"
    }

    fun toolRound(id: String, inputTokens: Int) = LLMResponse.ToolUse(
        calls = listOf(dev.sophi.ai.api.ToolCall(id, "ok", "{}")),
        usage = TokenUsage(inputTokens, 0)
    ).toStreamFlow()

    test("turn() compacts this turn's earlier rounds once input tokens cross the threshold") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry().register(loopingTool())
        // window 100_000 * threshold 0.8 => compact at 80_000 input tokens
        val loopWithTool = newLoop(toolRegistry, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)

        val capturedRequests = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            when (capturedRequests.size) {
                1 -> toolRound("c1", 1_000)
                2 -> toolRound("c2", 1_000)
                3 -> toolRound("c3", 90_000)
                else -> LLMResponse.Text("done", TokenUsage(1_000, 5)).toStreamFlow()
            }
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("r1 looked at the config", TokenUsage(1, 1))
        every { sessionManager.save(any()) } just Runs

        loopWithTool.turn(session, "go", config.copy(maxToolRounds = 20))

        // Exactly one summarisation call happened.
        coVerify(exactly = 1) { provider.complete(any()) }
        // The 4th request replaced round 1 with a SYSTEM summary and kept the 2 most recent rounds.
        val fourth = capturedRequests[3].messages
        fourth shouldHaveSize 6
        fourth[0].role shouldBe dev.sophi.ai.api.MessageRole.USER
        fourth[1].role shouldBe dev.sophi.ai.api.MessageRole.SYSTEM
        fourth[1].content shouldContain "Earlier steps this turn, summarised:"
        fourth[1].content shouldContain "r1 looked at the config"
    }

    test("compaction never orphans a TOOL result from its ASSISTANT tool-call message") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry().register(loopingTool())
        val loopWithTool = newLoop(toolRegistry, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)

        val capturedRequests = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            when (capturedRequests.size) {
                1 -> toolRound("c1", 1_000)
                2 -> toolRound("c2", 1_000)
                3 -> toolRound("c3", 90_000)
                else -> LLMResponse.Text("done", TokenUsage(1_000, 5)).toStreamFlow()
            }
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(1, 1))
        every { sessionManager.save(any()) } just Runs

        loopWithTool.turn(session, "go", config.copy(maxToolRounds = 20))

        val messages = capturedRequests[3].messages
        val advertisedCallIds = messages
            .flatMap { it.toolCalls.orEmpty() }
            .map { it.id }
            .toSet()
        val orphanedResults = messages
            .filter { it.role == dev.sophi.ai.api.MessageRole.TOOL }
            .filter { it.toolCallId !in advertisedCallIds }
        orphanedResults shouldBe emptyList()
        advertisedCallIds shouldBe setOf("c2", "c3")
    }

    test("turn() stops with a clear reason when even the compaction floor cannot free enough context") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry().register(loopingTool())
        val loopWithTool = newLoop(toolRegistry, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)

        // Round 1 alone already exceeds the threshold; with only one round there is nothing
        // older to summarise, so compaction cannot help.
        every { provider.stream(any()) } returns toolRound("c1", 90_000)
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "go", config.copy(maxToolRounds = 20))

        result.branch().last().content shouldContain "Stopped early"
        result.branch().last().content shouldContain "context budget exhausted"
        result.tip?.metadata?.get("stopReason") shouldBe TurnStopReason.ContextExhausted.name
        coVerify(exactly = 1) { provider.stream(any()) }
        coVerify(exactly = 0) { provider.complete(any()) }
    }

    test("turn() stops when compaction runs twice in a row without bringing context back under the threshold") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry().register(loopingTool())
        val loopWithTool = newLoop(toolRegistry, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)

        val capturedRequests = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            when (capturedRequests.size) {
                1 -> toolRound("c1", 1_000)
                2 -> toolRound("c2", 1_000)
                3 -> toolRound("c3", 90_000)   // compacts (relief counter -> 1)
                else -> toolRound("c4", 90_000) // compacts again, still no relief -> stop
            }
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(1, 1))
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "go", config.copy(maxToolRounds = 20))

        result.branch().last().content shouldContain "Stopped early"
        result.branch().last().content shouldContain "compaction is thrashing"
        result.tip?.metadata?.get("stopReason") shouldBe TurnStopReason.CompactionThrashing.name
        coVerify(exactly = 4) { provider.stream(any()) }
        coVerify(exactly = 2) { provider.complete(any()) }
    }

    test("a round back under the threshold clears the thrashing counter") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry().register(loopingTool())
        val loopWithTool = newLoop(toolRegistry, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)

        val capturedRequests = mutableListOf<dev.sophi.ai.api.CompletionRequest>()
        every { provider.stream(any()) } answers {
            capturedRequests.add(firstArg())
            when (capturedRequests.size) {
                1 -> toolRound("c1", 1_000)
                2 -> toolRound("c2", 1_000)
                3 -> toolRound("c3", 90_000)   // compacts (counter -> 1)
                4 -> toolRound("c4", 1_000)    // relief: counter back to 0
                5 -> toolRound("c5", 90_000)   // compacts again (counter -> 1), no stop
                else -> LLMResponse.Text("finished", TokenUsage(1_000, 5)).toStreamFlow()
            }
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(1, 1))
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "go", config.copy(maxToolRounds = 20))

        result.branch().last().content shouldBe "finished"
    }
})
