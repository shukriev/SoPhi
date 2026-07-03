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

    test("streamTurn() emits a Token event for each emitted chunk") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flowOf("Hello", " ", "World")

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "hi", config) { events.add(it) }

        events shouldBe listOf(TurnEvent.Token("Hello"), TurnEvent.Token(" "), TurnEvent.Token("World"))
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

    test("streamTurn() falls back to turn() and delivers its events when the stream emits nothing") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns emptyFlow()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("fallback", TokenUsage(0, 0))

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "hi", config) { events.add(it) }

        coVerify { provider.complete(any()) }
        events shouldBe listOf(TurnEvent.Token("fallback"))
        session.branch().last().content shouldBe "fallback"
    }

    test("streamTurn() falls back to turn() when the stream throws immediately") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flow { throw RuntimeException("stream error") }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("fallback", TokenUsage(0, 0))

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "hi", config) { events.add(it) }

        coVerify { provider.complete(any()) }
        events shouldBe listOf(TurnEvent.Token("fallback"))
        session.branch().last().content shouldBe "fallback"
    }

    test("streamTurn() delivers the partial token then the fallback's event when the stream emits then throws") {
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flow {
            emit("partial")
            throw RuntimeException("mid-stream error")
        }
        coEvery { provider.complete(any()) } returns LLMResponse.Text("fallback", TokenUsage(0, 0))

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "hi", config) { events.add(it) }

        coVerify { provider.complete(any()) }
        events shouldBe listOf(TurnEvent.Token("partial"), TurnEvent.Token("fallback"))
        session.branch().last().content shouldBe "fallback"
    }
})
