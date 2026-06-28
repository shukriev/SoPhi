package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify

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
        coEvery { provider.complete(any()) } returns LLMResponse.Text("Hello!", TokenUsage(10, 5))
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
        coEvery { provider.complete(any()) } returns LLMResponse.Text("OK", TokenUsage(1, 1))
        every { sessionManager.save(any()) } just Runs

        loop.turn(session, "test", config)

        verify(exactly = 1) { sessionManager.save(any()) }
    }

    test("turn() includes session history in the CompletionRequest messages") {
        val session = AgentSession(id = "s1")
        session.append(EntryRole.USER, "previous question")
        session.append(EntryRole.ASSISTANT, "previous answer")

        var capturedRequest: dev.sophi.ai.api.CompletionRequest? = null
        coEvery { provider.complete(any()) } answers {
            capturedRequest = firstArg()
            LLMResponse.Text("response", TokenUsage(1, 1))
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
        coEvery { provider.complete(any()) } answers {
            capturedRequest = firstArg()
            LLMResponse.Text("ok", TokenUsage(1, 1))
        }
        every { sessionManager.save(any()) } just Runs

        loop.turn(session, "test", config)

        capturedRequest!!.model shouldBe "test-model"
        capturedRequest!!.systemPrompt shouldBe "You are helpful."
    }

    test("turn() throws IllegalStateException on LLMResponse.Error") {
        val session = AgentSession(id = "s1")
        coEvery { provider.complete(any()) } returns LLMResponse.Error("provider down")

        shouldThrow<IllegalStateException> { loop.turn(session, "test", config) }
    }

    test("turn() does NOT modify session on error") {
        val session = AgentSession(id = "s1")
        coEvery { provider.complete(any()) } returns LLMResponse.Error("boom")

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

        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.ToolUse(
                calls = listOf(dev.sophi.ai.api.ToolCall("call-1", "calculator", "{}")),
                usage = TokenUsage(10, 0)
            ),
            LLMResponse.Text("The answer is 42", TokenUsage(5, 8))
        )
        every { sessionManager.save(any()) } just Runs

        val result = loopWithTool.turn(session, "What is 6 times 7?", config)

        result.branch() shouldHaveSize 2
        result.branch()[1].content shouldBe "The answer is 42"
        coVerify(exactly = 2) { provider.complete(any()) }
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
        coEvery { provider.complete(any()) } answers {
            capturedRequests.add(firstArg())
            if (capturedRequests.size == 1)
                LLMResponse.ToolUse(
                    calls = listOf(dev.sophi.ai.api.ToolCall("c1", "ping", "{}")),
                    usage = TokenUsage(1, 0)
                )
            else
                LLMResponse.Text("done", TokenUsage(1, 1))
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
        coEvery { provider.complete(any()) } answers {
            capturedRequests.add(firstArg())
            if (capturedRequests.size == 1)
                LLMResponse.ToolUse(
                    calls = listOf(dev.sophi.ai.api.ToolCall("c1", "nonexistent", "{}")),
                    usage = TokenUsage(1, 0)
                )
            else
                LLMResponse.Text("fallback", TokenUsage(1, 1))
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
        coEvery { provider.complete(any()) } answers {
            capturedRequests.add(firstArg())
            if (capturedRequests.size == 1)
                LLMResponse.ToolUse(
                    calls = listOf(dev.sophi.ai.api.ToolCall("c1", "broken", "{}")),
                    usage = TokenUsage(1, 0)
                )
            else
                LLMResponse.Text("recovered", TokenUsage(1, 1))
        }
        every { sessionManager.save(any()) } just Runs

        loopWithTool.turn(session, "test", config)

        val toolResultMsg = capturedRequests[1].messages.last()
        toolResultMsg.content shouldBe "Error: disk full"
    }

    test("turn() throws IllegalStateException when maxToolRounds exceeded") {
        val session = AgentSession(id = "s1")
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : dev.sophi.core.tools.Tool {
            override val name = "loop"
            override val description = "Always loops"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "still looping"
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager)
        val tightConfig = config.copy(maxToolRounds = 2)

        coEvery { provider.complete(any()) } returns LLMResponse.ToolUse(
            calls = listOf(dev.sophi.ai.api.ToolCall("c1", "loop", "{}")),
            usage = TokenUsage(1, 0)
        )

        shouldThrow<IllegalStateException> { loopWithTool.turn(session, "go", tightConfig) }
    }
})
