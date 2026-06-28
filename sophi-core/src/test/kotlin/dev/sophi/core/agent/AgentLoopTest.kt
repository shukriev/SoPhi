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
})
