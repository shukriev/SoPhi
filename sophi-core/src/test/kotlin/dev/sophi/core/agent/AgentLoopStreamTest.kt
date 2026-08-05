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
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
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
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "deleted"
        }
        val registry = ToolRegistry().register(destructiveTool)
        val loop = AgentLoop(provider, registry, sessionManager, confirmationPolicy = ConfirmationPolicy.DENY_ALL)
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

    test("streamTurn() emits ConfirmationStarted/ConfirmationFinished around a confirm() call for a non-SAFE tool, before ToolCallStarted") {
        val destructiveTool = object : Tool {
            override val name = "delete_file"
            override val description = ""
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
            override suspend fun execute(argumentsJson: String) = "deleted"
        }
        val registry = ToolRegistry().register(destructiveTool)
        val loop = AgentLoop(provider, registry, sessionManager, confirmationPolicy = ConfirmationPolicy.ALLOW_ALL)
        val session = AgentSession(id = "s6")
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1) flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("call_1", "delete_file", "{}"))))
            else flowOf(StreamEvent.Content("ok"))
        }

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "delete it", config) { events.add(it) }

        val startedIdx = events.indexOfFirst { it is TurnEvent.ConfirmationStarted }
        val finishedIdx = events.indexOfFirst { it is TurnEvent.ConfirmationFinished }
        val toolStartedIdx = events.indexOfFirst { it is TurnEvent.ToolCallStarted }
        startedIdx shouldBe 0
        (events[startedIdx] as TurnEvent.ConfirmationStarted).toolNames shouldBe listOf("delete_file")
        (finishedIdx > startedIdx) shouldBe true
        (toolStartedIdx > finishedIdx) shouldBe true
    }

    test("streamTurn() does not emit ConfirmationStarted/ConfirmationFinished for a SAFE tool call") {
        val safeTool = object : Tool {
            override val name = "read_file"
            override val description = ""
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "contents"
        }
        val registry = ToolRegistry().register(safeTool)
        val loop = AgentLoop(provider, registry, sessionManager)
        val session = AgentSession(id = "s7")
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1) flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("call_1", "read_file", "{}"))))
            else flowOf(StreamEvent.Content("ok"))
        }

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "read it", config) { events.add(it) }

        events.filterIsInstance<TurnEvent.ConfirmationStarted>() shouldBe emptyList()
        events.filterIsInstance<TurnEvent.ConfirmationFinished>() shouldBe emptyList()
    }

    test("streamTurn() stops gracefully at the maxToolRounds ceiling instead of throwing") {
        val tool = object : Tool {
            override val name = "loop_tool"
            override val description = ""
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "again"
        }
        val registry = ToolRegistry().register(tool)
        // ALWAYS_CONTINUE: see the equivalent AgentLoopTest.kt comment — the default guard would
        // otherwise stop this early via its own round-budget trigger before the ceiling.
        val loop = AgentLoop(provider, registry, sessionManager, loopGuard = LoopGuardPolicy.ALWAYS_CONTINUE)
        val session = AgentSession(id = "s6")
        val limitedConfig = AgentConfig(model = "test-model", maxToolRounds = 1)
        every { provider.stream(any()) } returns flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("call_1", "loop_tool", "{}"))))

        val events = mutableListOf<TurnEvent>()
        loop.streamTurn(session, "loop forever", limitedConfig) { events.add(it) }

        session.branch().last().content shouldBe "[Stopped early: reached the tool-round sanity ceiling (1)]"
        events.last() shouldBe TurnEvent.Token("[Stopped early: reached the tool-round sanity ceiling (1)]")
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

    // ── Loop guard ──────────────────────────────────────────────────────────

    test("streamTurn() stops early after 3 consecutive fully-failed rounds under the default guard") {
        val tool = object : Tool {
            override val name = "broken"
            override val description = ""
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String = throw RuntimeException("nope")
        }
        val registry = ToolRegistry().register(tool)
        val loop = AgentLoop(provider, registry, sessionManager)
        val session = AgentSession(id = "s8")
        every { provider.stream(any()) } returns flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "broken", "{}"))))

        loop.streamTurn(session, "go", config.copy(maxToolRounds = 20)) {}

        session.branch().last().content shouldContain "Stopped early"
        session.branch().last().content shouldContain "3 consecutive"
        coVerify(exactly = 3) { provider.stream(any()) }
    }

    test("streamTurn() stops early when a glob/grep search broadens beyond an earlier scoped path") {
        val tool = object : Tool {
            override val name = "glob"
            override val description = ""
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "no matches"
        }
        val registry = ToolRegistry().register(tool)
        val loop = AgentLoop(provider, registry, sessionManager)
        val session = AgentSession(id = "s9")
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1) flowOf(StreamEvent.ToolCallsReady(
                listOf(ToolCall("c1", "glob", """{"path":"transcribe","pattern":"*.txt"}"""))
            ))
            else flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c2", "glob", """{"pattern":"**/*transcribe*"}"""))))
        }

        loop.streamTurn(session, "go", config.copy(maxToolRounds = 20)) {}

        session.branch().last().content shouldContain "Stopped early"
        session.branch().last().content shouldContain "broadened"
        coVerify(exactly = 2) { provider.stream(any()) }
    }

    test("streamTurn() stops early when approaching the tool-round budget under the default guard") {
        val tool = object : Tool {
            override val name = "ok"
            override val description = ""
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "fine"
        }
        val registry = ToolRegistry().register(tool)
        val loop = AgentLoop(provider, registry, sessionManager)
        val session = AgentSession(id = "s10")
        every { provider.stream(any()) } returns flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "ok", "{}"))))

        loop.streamTurn(session, "go", config.copy(maxToolRounds = 4)) {}

        session.branch().last().content shouldContain "Stopped early"
        session.branch().last().content shouldContain "round"
        coVerify(exactly = 1) { provider.stream(any()) }
    }
})
