package dev.sophi.core.agent

import dev.sophi.ai.api.BudgetExceededException
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class AgentLoopHandoffTest : FunSpec({
    val config = AgentConfig(model = "test-model", systemPrompt = "You are helpful.")
    val requests = mutableListOf<CompletionRequest>()

    fun registry(fails: Boolean = false) = ToolRegistry().register(object : Tool {
        override val name = "loop"
        override val description = "Always loops"
        override val parametersJson = "{}"
        override suspend fun execute(argumentsJson: String): String = if (fails) throw RuntimeException("nope") else "still looping"
    })

    /** Tool rounds always call `loop`; the tool-less note round streams [note]. */
    fun loop(note: () -> Flow<StreamEvent>, fails: Boolean = false, guard: LoopGuardPolicy = LoopGuardPolicy.ALWAYS_CONTINUE): AgentLoop {
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        requests.clear()
        every { provider.stream(any()) } answers {
            val req = firstArg<CompletionRequest>().also { requests += it }
            if (req.tools.isEmpty()) note()
            else LLMResponse.ToolUse(listOf(ToolCall("c${requests.size}", "loop", "{}")), TokenUsage(1, 0)).toStreamFlow()
        }
        every { sm.save(any()) } just Runs
        return AgentLoop(provider, registry(fails), sm, loopGuard = guard, contextWindowTokens = 100_000)
    }
    val note = "## Done\nread a.kt\n## Next\nedit b.kt\n## Traps\nnone"

    test("the tool-round ceiling pauses the turn with a tool-less handoff note") {
        val session = AgentSession(id = "s1")
        val tokens = StringBuilder()
        loop({ flowOf(StreamEvent.Content(note)) }).streamTurn(session, "go", config.copy(maxToolRounds = 2)) {
            if (it is TurnEvent.Token) tokens.append(it.text)
        }

        val tip = session.branch().last()
        tip.content shouldStartWith "[Paused: reached the tool-round sanity ceiling (2)]"
        tip.content shouldContain note
        tip.metadata["stopReason"] shouldBe TurnStopReason.ToolRoundCeiling.name
        tip.metadata["paused"] shouldBe "true"
        val noteRequest = requests.last()
        noteRequest.tools shouldBe emptyList()
        noteRequest.messages.last().role shouldBe MessageRole.USER
        noteRequest.messages.last().content shouldBe HANDOFF_PROMPT
        tokens.toString() shouldBe tip.content
    }

    test("the loop guard pauses the turn with a handoff note") {
        val session = AgentSession(id = "s1")
        loop({ flowOf(StreamEvent.Content(note)) }, fails = true, guard = LoopGuardPolicy.NEVER_CONTINUE)
            .turn(session, "go", config.copy(maxToolRounds = 20))

        val tip = session.branch().last()
        tip.content shouldStartWith "[Paused: "
        tip.content shouldContain note
        tip.metadata["stopReason"] shouldBe TurnStopReason.LoopGuard.name
        tip.metadata["paused"] shouldBe "true"
        requests.last().tools shouldBe emptyList()
    }

    test("a failing, over-budget or blank note call falls back to the plain early stop") {
        listOf<() -> Flow<StreamEvent>>(
            { flow { throw IllegalStateException("provider down") } },
            { flow { throw BudgetExceededException("[Stopped: budget]") } },
            { flowOf(StreamEvent.Content("  ")) },
        ).forEach { failing ->
            val session = AgentSession(id = "s1")
            loop(failing).turn(session, "go", config.copy(maxToolRounds = 2))
            val tip = session.branch().last()
            tip.content shouldBe "[Stopped early: reached the tool-round sanity ceiling (2)]"
            tip.metadata shouldNotContainKey "paused"
            tip.metadata["stopReason"] shouldBe TurnStopReason.ToolRoundCeiling.name
            session.branch().filter { it.metadata.containsKey("stopReason") } shouldHaveSize 1
        }
    }

    test("cancellation during the note call propagates and saves the turn as stopped by the user") {
        val session = AgentSession(id = "s1")
        shouldThrow<CancellationException> {
            loop({ flow { throw CancellationException("stop") } }).turn(session, "go", config.copy(maxToolRounds = 2))
        }
        session.branch().last().content shouldBe STOPPED_BY_USER
        session.branch().last().metadata["stopReason"] shouldBe TurnStopReason.UserStopped.name
    }

    test("other early stops make no note call") {
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        var calls = 0
        every { provider.stream(any()) } answers { calls++; flowOf(StreamEvent.Reasoning("hmm")) }
        every { sm.save(any()) } just Runs
        val session = AgentSession(id = "s1")
        AgentLoop(provider, ToolRegistry(), sm, contextWindowTokens = 100_000).turn(session, "go", config)

        calls shouldBe 1
        session.branch().last().metadata["stopReason"] shouldBe TurnStopReason.EmptyResponse.name
        session.branch().last().metadata shouldNotContainKey "paused"
    }
})
