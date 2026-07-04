package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class TurnControllerTest : FunSpec({
    val provider = mockk<LLMProvider>()
    val sessionManager = mockk<SessionManager>(relaxed = true)
    val config = AgentConfig(model = "test-model")
    val loop = AgentLoop(provider, ToolRegistry(), sessionManager)

    beforeTest { clearMocks(provider, sessionManager) }

    test("runTurn() streams tokens and renders the final response to output") {
        every { provider.stream(any()) } returns flowOf("Hello", " ", "World")
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(loop, config, input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }

        controller.runTurn(AgentSession(id = "s1"), "hi")

        rendered shouldBe listOf(ResponseRenderer.renderText("Hello World"))
    }

    test("runTurn() renders a tool-call block, then the final response") {
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : Tool {
            override val name = "ping"
            override val description = "Pings"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "pong"
        })
        val loopWithTool = AgentLoop(provider, toolRegistry, sessionManager)
        every { provider.stream(any()) } returns emptyFlow()
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.ToolUse(calls = listOf(ToolCall("c1", "ping", "{}")), usage = TokenUsage(1, 0)),
            LLMResponse.Text("done", TokenUsage(1, 1))
        )
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(loopWithTool, config, input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }

        controller.runTurn(AgentSession(id = "s1"), "ping")

        rendered shouldBe listOf(
            ResponseRenderer.renderToolCall("ping", "{}", "pong"),
            ResponseRenderer.renderText("done")
        )
    }

    test("runTurn() cancels the stream and returns the original session when ESC arrives mid-turn") {
        val input = ScriptedInputSource(emptyList())
        every { provider.stream(any()) } returns flow {
            emit("partial")
            input.signalEsc()
            delay(Long.MAX_VALUE)
        }
        val rendered = mutableListOf<String>()
        val controller = TurnController(loop, config, input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }
        val session = AgentSession(id = "s1")

        val result = controller.runTurn(session, "hi")

        result shouldBe session
        rendered shouldBe listOf(ResponseRenderer.renderText("partial") + " [interrupted]")
    }

    test("runTurn() surfaces a provider error as an output line instead of throwing") {
        every { provider.stream(any()) } returns flow { throw RuntimeException("stream error") }
        coEvery { provider.complete(any()) } returns LLMResponse.Error("boom")
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(loop, config, input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }
        val session = AgentSession(id = "s1")

        val result = controller.runTurn(session, "hi")

        result shouldBe session
        rendered shouldBe listOf(ResponseRenderer.renderText("") + " [error: LLM error: boom]")
    }
})
