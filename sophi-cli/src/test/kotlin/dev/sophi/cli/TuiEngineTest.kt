package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf

class TuiEngineTest : FunSpec({
    val provider = mockk<LLMProvider>()
    val sessionManager = mockk<SessionManager>(relaxed = true)
    val config = AgentConfig(model = "test-model")
    val loop = AgentLoop(provider, ToolRegistry(), sessionManager)
    val slashOutput = mutableListOf<String>()
    val slashHandler = SlashHandler(sessionManager, null, config) { slashOutput.add(it) }

    beforeTest {
        clearMocks(provider, sessionManager)
        slashOutput.clear()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("response"))
    }

    fun buildEngine(lines: List<String>): TuiEngine {
        val input = ScriptedInputSource(lines)
        val turnController = TurnController(loop, config, input, LiveRegion(StringBuilder()) { 80 }) {}
        return TuiEngine(turnController, slashHandler, input)
    }

    test("run() calls provider.stream() once for each regular input line") {
        buildEngine(listOf("hello", "exit")).run(AgentSession(id = "s1"))
        verify(exactly = 1) { provider.stream(any()) }
    }

    test("run() routes slash commands to slashHandler without calling stream()") {
        every { sessionManager.list() } returns emptyList()
        buildEngine(listOf("/list", "exit")).run(AgentSession(id = "s1"))
        verify(exactly = 0) { provider.stream(any()) }
        slashOutput shouldBe listOf("No saved sessions.")
    }

    test("run() stops on 'exit' before any provider call") {
        buildEngine(listOf("exit", "unreachable")).run(AgentSession(id = "s1"))
        verify(exactly = 0) { provider.stream(any()) }
    }

    test("run() stops on 'quit'") {
        buildEngine(listOf("quit")).run(AgentSession(id = "s1"))
        verify(exactly = 0) { provider.stream(any()) }
    }

    test("run() skips blank and empty lines") {
        buildEngine(listOf("", "  ", "hi", "")).run(AgentSession(id = "s1"))
        verify(exactly = 1) { provider.stream(any()) }
    }

    test("run() stops when the input source is exhausted (EOF)") {
        buildEngine(emptyList()).run(AgentSession(id = "s1"))
        verify(exactly = 0) { provider.stream(any()) }
    }
})
