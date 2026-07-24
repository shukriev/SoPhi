package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicInteger

class AgentLoopStreamTest : FunSpec({
    val provider = mockk<LLMProvider>()
    val sessionManager = mockk<SessionManager>(relaxed = true)
    val config = AgentConfig(model = "test-model")

    beforeTest { clearMocks(provider, sessionManager) }

    test("streamTurn() emits a Token event per Content chunk and persists the final answer") {
        val loop = AgentLoop(provider, ToolRegistry(), sessionManager)
        val session = AgentSession(id = "s1")
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("Hello"), StreamEvent.Content(" World"))

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "hi", config) { events.add(it) }

        events shouldBe listOf(TurnEvent.Token("Hello"), TurnEvent.Token(" World"))
        val branch = session.branch()
        branch shouldHaveSize 2
        branch[0].role shouldBe EntryRole.USER
        branch[1].role shouldBe EntryRole.ASSISTANT
        branch[1].content shouldBe "Hello World"
    }

    test("streamTurn() emits a ReasoningToken event per Reasoning chunk, not counted as content") {
        val loop = AgentLoop(provider, ToolRegistry(), sessionManager)
        val session = AgentSession(id = "s2")
        every { provider.stream(any()) } returns flowOf(
            StreamEvent.Reasoning("thinking..."), StreamEvent.Content("answer")
        )

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "hi", config) { events.add(it) }

        events shouldBe listOf(TurnEvent.ReasoningToken("thinking..."), TurnEvent.Token("answer"))
        session.branch().last().content shouldBe "answer"
    }

    test("streamTurn() executes a tool call from ToolCallsReady, loops, then persists the final answer") {
        val tool = object : Tool {
            override val name = "get_weather"
            override val description = "Weather"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "sunny"
        }
        val registry = ToolRegistry().register(tool)
        val loop = AgentLoop(provider, registry, sessionManager)
        val session = AgentSession(id = "s3")
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1) {
                flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("call_1", "get_weather", "{}"))))
            } else {
                flowOf(StreamEvent.Content("It's sunny"))
            }
        }

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "weather?", config) { events.add(it) }

        events.filterIsInstance<TurnEvent.ToolCallStarted>() shouldHaveSize 1
        events.filterIsInstance<TurnEvent.ToolCallFinished>() shouldHaveSize 1
        events.last() shouldBe TurnEvent.Token("It's sunny")
        session.branch().last().content shouldBe "It's sunny"
    }

    test("streamTurn() dispatches multiple tool calls from one round concurrently") {
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val slowTool = object : Tool {
            override val name = "slow"
            override val description = ""
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String {
                val now = inFlight.incrementAndGet()
                maxObserved.updateAndGet { maxOf(it, now) }
                kotlinx.coroutines.delay(30)
                inFlight.decrementAndGet()
                return "done"
            }
        }
        val registry = ToolRegistry().register(slowTool)
        val loop = AgentLoop(provider, registry, sessionManager)
        val session = AgentSession(id = "s4")
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1) {
                flowOf(StreamEvent.ToolCallsReady(listOf(
                    ToolCall("call_a", "slow", "{}"), ToolCall("call_b", "slow", "{}")
                )))
            } else {
                flowOf(StreamEvent.Content("both done"))
            }
        }

        loop.streamTurn(session, "go", config) {}

        (maxObserved.get() >= 2) shouldBe true
    }

    test("streamTurn() denies a DESTRUCTIVE tool call the confirmation policy rejects") {
        val destructiveTool = object : Tool {
            override val name = "delete_file"
            override val description = ""
            override val parametersJson = "{}"
            override val riskLevel = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "deleted"
        }
        val registry = ToolRegistry().register(destructiveTool)
        val loop = AgentLoop(provider, registry, sessionManager, confirmationPolicy = ConfirmationPolicy.DENY_DESTRUCTIVE)
        val session = AgentSession(id = "s5")
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1) flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("call_1", "delete_file", "{}"))))
            else flowOf(StreamEvent.Content("ok"))
        }

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "delete it", config) { events.add(it) }

        val finished = events.filterIsInstance<TurnEvent.ToolCallFinished>().single()
        finished.isError shouldBe true
        finished.result shouldBe "Error: Tool 'delete_file' execution denied by confirmation policy"
    }

    test("streamTurn() throws IllegalStateException when maxToolRounds is exceeded") {
        val tool = object : Tool {
            override val name = "loop_tool"
            override val description = ""
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "again"
        }
        val registry = ToolRegistry().register(tool)
        val loop = AgentLoop(provider, registry, sessionManager)
        val session = AgentSession(id = "s6")
        val limitedConfig = AgentConfig(model = "test-model", maxToolRounds = 1)
        every { provider.stream(any()) } returns flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("call_1", "loop_tool", "{}"))))

        try {
            loop.streamTurn(session, "loop forever", limitedConfig) {}
            error("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            e.message shouldBe "Max tool rounds (1) exceeded"
        }
    }

    test("streamTurn() propagates a stream failure as an error, with no fallback") {
        val loop = AgentLoop(provider, ToolRegistry(), sessionManager)
        val session = AgentSession(id = "s7")
        every { provider.stream(any()) } returns flow { throw RuntimeException("stream error") }

        try {
            loop.streamTurn(session, "hi", config) {}
            error("expected an exception")
        } catch (e: Exception) {
            e.message shouldBe "stream error"
        }
    }
})
