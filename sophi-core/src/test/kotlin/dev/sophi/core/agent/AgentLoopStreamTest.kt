package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class AgentLoopStreamTest : FunSpec({
    val provider = mockk<LLMProvider>()
    val sessionManager = mockk<SessionManager>(relaxed = true)
    val config = AgentConfig(model = "test-model")
    val loop = AgentLoop(provider, ToolRegistry(), sessionManager)

    beforeTest { clearMocks(provider, sessionManager) }

    test("streamTurn() calls onToken for each emitted token") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flowOf("Hello", " ", "World")

        val tokens = mutableListOf<String>()
        loop.streamTurn(session, "hi", config) { tokens.add(it) }

        tokens shouldBe listOf("Hello", " ", "World")
    }

    test("streamTurn() appends USER then ASSISTANT entry from accumulated tokens") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flowOf("streamed", " response")

        loop.streamTurn(session, "my question", config) {}

        val branch = session.branch()
        branch shouldHaveSize 2
        branch[0].role shouldBe EntryRole.USER
        branch[0].content shouldBe "my question"
        branch[1].role shouldBe EntryRole.ASSISTANT
        branch[1].content shouldBe "streamed response"
    }

    test("streamTurn() falls back to complete() when stream emits nothing") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns emptyFlow()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("fallback", TokenUsage(0, 0))

        val tokens = mutableListOf<String>()
        loop.streamTurn(session, "hi", config) { tokens.add(it) }

        coVerify { provider.complete(any()) }
        tokens shouldBe emptyList()
        session.branch().last().content shouldBe "fallback"
    }

    test("streamTurn() falls back to complete() when stream throws") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flow { throw RuntimeException("stream error") }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("fallback", TokenUsage(0, 0))

        loop.streamTurn(session, "hi", config) {}

        coVerify { provider.complete(any()) }
        session.branch().last().content shouldBe "fallback"
    }

    test("streamTurn() falls back to complete() when stream emits tokens then throws") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flow {
            emit("partial")
            throw RuntimeException("mid-stream error")
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("fallback", TokenUsage(0, 0))

        loop.streamTurn(session, "hi", config) {}

        coVerify { provider.complete(any()) }
        session.branch().last().content shouldBe "fallback"
    }
})
