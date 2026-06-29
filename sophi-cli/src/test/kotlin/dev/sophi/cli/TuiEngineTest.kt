package dev.sophi.cli

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.ai.api.LLMProvider
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
    val terminal = Terminal(ansiLevel = AnsiLevel.NONE)
    val slashOutput = mutableListOf<String>()
    val slashHandler = SlashHandler(sessionManager, null, config) { slashOutput.add(it) }
    lateinit var engine: TuiEngine

    beforeTest {
        clearMocks(provider, sessionManager)
        slashOutput.clear()
        every { provider.stream(any()) } returns flowOf("response")
        engine = TuiEngine(loop, slashHandler, config, terminal)
    }

    test("run() calls provider.stream() once for each regular input line") {
        val session = AgentSession(id = "s1")
        engine.run(session, sequenceOf("hello", "exit"))
        verify(exactly = 1) { provider.stream(any()) }
    }

    test("run() routes slash commands to slashHandler without calling stream()") {
        val session = AgentSession(id = "s1")
        every { sessionManager.list() } returns emptyList()
        engine.run(session, sequenceOf("/list", "exit"))
        verify(exactly = 0) { provider.stream(any()) }
        slashOutput shouldBe listOf("No saved sessions.")
    }

    test("run() stops on 'exit' before any provider call") {
        val session = AgentSession(id = "s1")
        engine.run(session, sequenceOf("exit", "unreachable"))
        verify(exactly = 0) { provider.stream(any()) }
    }

    test("run() stops on 'quit'") {
        val session = AgentSession(id = "s1")
        engine.run(session, sequenceOf("quit"))
        verify(exactly = 0) { provider.stream(any()) }
    }

    test("run() skips blank and empty lines") {
        val session = AgentSession(id = "s1")
        engine.run(session, sequenceOf("", "  ", "hi", ""))
        verify(exactly = 1) { provider.stream(any()) }
    }
})
