package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import dev.sophi.learning.JsonlLog
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicInteger

class TurnControllerTest : FunSpec({
    val provider = mockk<LLMProvider>()
    val sessionManager = mockk<SessionManager>(relaxed = true)
    val config = AgentConfig(model = "test-model")
    val loop = AgentLoop(provider, ToolRegistry(), sessionManager)

    beforeTest { clearMocks(provider, sessionManager) }

    test("runTurn() streams tokens and renders the final response to output") {
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("Hello"), StreamEvent.Content(" "), StreamEvent.Content("World"))
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(loop, config, input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }

        controller.runTurn(AgentSession(id = "s1"), "hi")

        rendered shouldBe listOf(ResponseRenderer.renderText("Hello World"))
    }

    test("runTurn() keeps reasoning visible in the output after the turn completes") {
        every { provider.stream(any()) } returns flowOf(StreamEvent.Reasoning("thinking..."), StreamEvent.Content("done"))
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(loop, config, input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }

        controller.runTurn(AgentSession(id = "s1"), "hi")

        rendered shouldBe listOf(ResponseRenderer.renderReasoning("thinking..."), ResponseRenderer.renderText("done"))
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
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1) {
                flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "ping", "{}"))))
            } else {
                flowOf(StreamEvent.Content("done"))
            }
        }
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(loopWithTool, config, input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }

        controller.runTurn(AgentSession(id = "s1"), "ping")

        rendered shouldBe listOf(
            ResponseRenderer.renderToolCall("ping", "{}", "pong"),
            ResponseRenderer.renderText("done")
        )
    }

    test("runTurn() dispatches AFTER_TURN via onTurnSettled so a registry-backed plugin records it") {
        val home = tempdir().toPath()
        val learning = LearningPlugin(LearningConfig(home = home, scope = "/proj"), model = "test-model")
        val registry = PluginRegistry().register(learning)
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("Hello"))
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(
            loop, config, input, LiveRegion(StringBuilder()) { 80 },
            onTurnSettled = { userInput, assistantReply, error ->
                if (error != null) registry.dispatch(HookPoint.ON_ERROR, HookContext("s1", error = error))
                else registry.dispatch(HookPoint.AFTER_TURN, HookContext("s1", userInput = userInput, assistantReply = assistantReply))
            }
        ) { rendered.add(it) }

        controller.runTurn(AgentSession(id = "s1"), "hi")

        val line = JsonlLog(home.resolve("session-outcomes.jsonl")).readAll().single()
        line shouldContain "\"outcome\":\"open\""
        line shouldContain "\"turns\":1"
    }

    test("runTurn() cancels the stream and returns the original session when ESC arrives mid-turn") {
        val input = ScriptedInputSource(emptyList())
        every { provider.stream(any()) } returns flow {
            emit(StreamEvent.Content("partial"))
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

    test("runTurn() pauses the live region's spinner while a confirmation is pending, resuming once it resolves") {
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : Tool {
            override val name = "danger"
            override val description = "risky"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "ran"
        })
        val releaseConfirmation = CompletableDeferred<Unit>()
        val confirmationPolicy = ConfirmationPolicy { requests: List<ConfirmationRequest> ->
            releaseConfirmation.await()
            requests.associate { it.callId to true }
        }
        val loopWithConfirmation = AgentLoop(
            provider, toolRegistry, sessionManager, confirmationPolicy = confirmationPolicy
        )
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1) flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "danger", "{}"))))
            else flowOf(StreamEvent.Content("done"))
        }
        val appendCount = AtomicInteger(0)
        val recordingAppendable = object : Appendable {
            override fun append(csq: CharSequence?): Appendable { appendCount.incrementAndGet(); return this }
            override fun append(csq: CharSequence?, start: Int, end: Int): Appendable { appendCount.incrementAndGet(); return this }
            override fun append(c: Char): Appendable { appendCount.incrementAndGet(); return this }
        }
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(
            loopWithConfirmation, config, input, LiveRegion(recordingAppendable) { 80 }
        ) { rendered.add(it) }

        coroutineScope {
            val turnJob = async { controller.runTurn(AgentSession(id = "s8"), "do it") }
            // Long enough for the first LLM round to return and the confirmation gate to engage
            // (animationJob's own 100ms cadence means the spinner would already have ticked at
            // least once before this point, which is fine -- we only care about growth *after*).
            delay(150)
            val countWhilePending = appendCount.get()
            // Several animation-job cadences (100ms each) while still blocked on
            // releaseConfirmation -- if the spinner weren't paused, this window alone would
            // produce multiple additional appends.
            delay(350)
            appendCount.get() shouldBe countWhilePending

            releaseConfirmation.complete(Unit)
            turnJob.await()
        }

        (appendCount.get() > 0).shouldBeTrue()
        rendered shouldBe listOf(
            ResponseRenderer.renderToolCall("danger", "{}", "ran"),
            ResponseRenderer.renderText("done")
        )
    }

    test("runTurn() surfaces a provider error as an output line instead of throwing") {
        every { provider.stream(any()) } returns flow { throw RuntimeException("stream error") }
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(loop, config, input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }
        val session = AgentSession(id = "s1")

        val result = controller.runTurn(session, "hi")

        result shouldBe session
        rendered shouldBe listOf(ResponseRenderer.renderText("") + " [error: stream error]")
    }
})
