package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.prompt.PromptBuilder
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow

class AgentLoopSteerTest : FunSpec({
    val config = AgentConfig(model = "test-model", systemPrompt = "You are helpful.")
    val call = ToolCall("c1", "look", "{}")
    fun use() = LLMResponse.ToolUse(listOf(call), TokenUsage(1, 0))
    fun text(s: String) = LLMResponse.Text(s, TokenUsage(1, 1))

    val requests = mutableListOf<CompletionRequest>()
    val saved = mutableListOf<AgentSession>()
    val events = mutableListOf<TurnEvent>()

    // round(n) may override how a round streams; default is the scripted response.
    fun scripted(vararg rounds: LLMResponse, onRound: (Int) -> Unit = {}): Pair<LLMProvider, SessionManager> {
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        var n = 0
        requests.clear(); saved.clear(); events.clear()
        every { provider.stream(any()) } answers {
            requests += firstArg<CompletionRequest>()
            val i = n++
            onRound(i)
            rounds[i.coerceAtMost(rounds.size - 1)].toStreamFlow()
        }
        every { sm.save(any()) } answers { saved += firstArg<AgentSession>() }
        return provider to sm
    }

    fun loop(provider: LLMProvider, sm: SessionManager, run: suspend () -> Unit = {}): AgentLoop {
        val tool = object : Tool {
            override val name = "look"
            override val description = "fake"
            override val parametersJson = "{}"
            override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE
            override suspend fun execute(argumentsJson: String): String { run(); return "ok" }
        }
        return AgentLoop(provider, ToolRegistry().apply { register(tool) }, sm, contextWindowTokens = 100_000)
    }

    test("a steer sent during round 1 appears in round 2's request") {
        val inbox = TurnInbox()
        val (provider, sm) = scripted(use(), text("done"))
        loop(provider, sm) { inbox.steer("use port 8080") }
            .turn(AgentSession(id = "s"), "go", config, inbox = inbox) { events += it }

        requests[1].messages.last().role shouldBe MessageRole.USER
        requests[1].messages.last().content shouldBe "use port 8080"
        events.filterIsInstance<TurnEvent.SteerApplied>().single().texts shouldBe listOf("use port 8080")
    }

    test("steers are saved with the turn, in order, and replay next turn") {
        val inbox = TurnInbox()
        val (provider, sm) = scripted(use(), text("done"))
        loop(provider, sm) { inbox.steer("use port 8080") }
            .turn(AgentSession(id = "s"), "go", config, inbox = inbox)

        val entries = saved.single().branch()
        entries.map { it.role } shouldBe listOf(
            EntryRole.USER, EntryRole.ASSISTANT, EntryRole.TOOL_RESULT, EntryRole.USER, EntryRole.ASSISTANT)
        entries[1].metadata["replay"] shouldBe "false"
        entries[2].metadata["replay"] shouldBe "false"
        entries[3].content shouldBe "use port 8080"
        entries[3].metadata["steer"] shouldBe "true"
        entries[3].metadata["replay"] shouldBe null
        entries[4].content shouldBe "done"
        PromptBuilder.build(entries).map { it.role to it.content } shouldBe listOf(
            MessageRole.USER to "go\n\nuse port 8080", MessageRole.ASSISTANT to "done")
    }

    test("steer queued during the final round continues the turn") {
        val inbox = TurnInbox()
        val (provider, sm) = scripted(text("answer A"), text("answer B")) { if (it == 0) inbox.steer("also B") }
        val session = loop(provider, sm).turn(AgentSession(id = "s"), "go", config, inbox = inbox)

        requests.size shouldBe 2
        requests[1].messages.takeLast(2).map { it.role to it.content } shouldBe
            listOf(MessageRole.ASSISTANT to "answer A", MessageRole.USER to "also B")
        session.branch().last().content shouldBe "answer B"
    }

    test("an applied steer survives an interrupt") {
        val inbox = TurnInbox()
        val started = CompletableDeferred<Unit>()
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        saved.clear()
        var n = 0
        every { provider.stream(any()) } answers {
            if (n++ == 0) use().toStreamFlow() else flow<StreamEvent> { started.complete(Unit); awaitCancellation() }
        }
        every { sm.save(any()) } answers { saved += firstArg<AgentSession>() }

        val job = async(start = CoroutineStart.UNDISPATCHED) {
            loop(provider, sm) { inbox.steer("x") }.turn(AgentSession(id = "s"), "go", config, inbox = inbox)
        }
        started.await()
        job.cancel()
        runCatching { job.await() }

        val entries = saved.single().branch()
        entries.any { it.role == EntryRole.USER && it.content == "x" && it.metadata["steer"] == "true" } shouldBe true
        entries.last().content shouldBe STOPPED_BY_USER
    }

    test("no inbox means no change") {
        val (provider, sm) = scripted(use(), text("done"))
        loop(provider, sm).turn(AgentSession(id = "s"), "go", config) { events += it }

        requests.size shouldBe 2
        requests[1].messages.last().role shouldBe MessageRole.TOOL
        events.filterIsInstance<TurnEvent.SteerApplied>() shouldBe emptyList()
    }
})
