package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.learning.JsonlLog
import dev.sophi.sdk.Sophi
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout

private const val TEST_CONTEXT_WINDOW = 100_000

class TuiEngineTest : FunSpec({
    val provider = mockk<LLMProvider>()
    val sessionManager = mockk<SessionManager>(relaxed = true)
    val config = AgentConfig(model = "test-model")
    // Built through the public builder: SophiRuntime's constructor is internal to sophi-sdk,
    // and this is the same path sophi-cli takes. ALLOW_ALL is explicit because RuntimeBuilder
    // defaults to DENY_ALL where a bare AgentLoop defaulted to ALLOW_ALL.
    val runtime = Sophi.runtime {
        this.provider = provider
        model = "test-model"
        contextWindowTokens(TEST_CONTEXT_WINDOW)
        sessionsDir = tempdir().toPath()
        confirmationPolicy(ConfirmationPolicy.ALLOW_ALL)
    }
    val slashOutput = mutableListOf<String>()
    val slashHandler = SlashHandler(sessionManager, null, config) { slashOutput.add(it) }

    beforeTest {
        clearMocks(provider, sessionManager)
        slashOutput.clear()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("response"))
    }

    fun buildEngine(lines: List<String>): TuiEngine {
        val input = ScriptedInputSource(lines)
        val turnController = TurnController(runtime, input, LiveRegion(StringBuilder()) { 80 }) {}
        return TuiEngine(turnController, slashHandler, input)
    }

    // ---- End-to-end: the real migrated stack, assembled by buildCliRuntime ----

    fun e2eOptions(dir: java.nio.file.Path) = CliOptions(
        model = "test-model",
        maxTokens = 4096,
        contextWindowTokens = TEST_CONTEXT_WINDOW,
        systemPrompt = null,
        sessionsDir = dir.resolve("sessions").toString(),
        agentsDir = dir.resolve("agents").toString(),
        scheduleDir = dir.resolve("schedule").toString(),
        plansDir = dir.resolve("plans").toString(),
        mcpConfigPath = dir.resolve("mcp.json").toString(),
        // Points learning at the test's own directory; LearningConfig otherwise defaults to the
        // developer's real ~/.sophi/learning, which would make the outcome assertion below read
        // whatever happens to be on that machine.
        learningHome = dir.resolve("learning"),
        noRemote = true
    )

    test("a turn driven through the migrated stack persists the assistant reply to the session") {
        val dir = tempdir().toPath()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("hello!"))
        val cli = buildCliRuntime(
            opts = e2eOptions(dir), provider = provider,
            terminal = com.github.ajalt.mordant.terminal.Terminal(),
            input = ScriptedInputSource(emptyList())
        )
        val input = ScriptedInputSource(listOf("hi", "exit"))
        val controller = TurnController(cli.runtime, input, LiveRegion(StringBuilder()) { 80 }) {}
        TuiEngine(controller, slashHandler, input).run(cli.session)

        val saved = cli.runtime.sessionManager.load(cli.session.id)
        saved.entries.any { it.role == EntryRole.ASSISTANT && it.content == "hello!" } shouldBe true
    }

    test("an interrupted turn still records the session as completed, not errored") {
        val dir = tempdir().toPath()
        val input = ScriptedInputSource(emptyList())
        every { provider.stream(any()) } returns flow {
            emit(StreamEvent.Content("par"))
            input.signalEsc()
            delay(Long.MAX_VALUE)
        }
        val cli = buildCliRuntime(
            opts = e2eOptions(dir), provider = provider,
            terminal = com.github.ajalt.mordant.terminal.Terminal(),
            input = ScriptedInputSource(emptyList())
        )
        val rendered = mutableListOf<String>()
        val controller = TurnController(cli.runtime, input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }

        controller.runTurn(cli.session, "hi")
        cli.runtime.learningPlugin!!.recordSessionEnd(cli.session.id)

        // Guards against this passing vacuously: the turn must really have been interrupted
        // rather than completing normally.
        rendered shouldBe listOf(ResponseRenderer.renderText("par") + " [interrupted]")

        // The whole point of the cancellation work: an interrupt is not a failure, and the turn
        // is still counted. Before the fix this wrote "error" and turns=0.
        val outcomes = JsonlLog(dir.resolve("learning").resolve("session-outcomes.jsonl")).readAll()
        outcomes.last() shouldContain "\"outcome\":\"completed\""
        outcomes.last() shouldContain "\"turns\":1"
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

    test("run() processes a hub-originated message the same way as a typed line") {
        val input = object : InputSource {
            // Never returns — simulates the user not typing anything, so the hub message must
            // be what actually drives the loop forward.
            override suspend fun readLine(): String? { delay(Long.MAX_VALUE); return null }
            override suspend fun awaitEsc() { delay(Long.MAX_VALUE) }
            override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit) { delay(Long.MAX_VALUE) }
            override suspend fun awaitYesNo(): Boolean = false
        }
        val turnController = TurnController(runtime, input, LiveRegion(StringBuilder()) { 80 }) {}
        val hubMessages = Channel<String>(Channel.UNLIMITED)
        val engine = TuiEngine(turnController, slashHandler, input, hubMessages)

        hubMessages.trySend("hello from companion")
        hubMessages.trySend("exit")

        withTimeout(5000) { engine.run(AgentSession(id = "s1")) }
        verify(exactly = 1) { provider.stream(any()) }
    }

    test("run() still responds to a typed line when a hub channel is present but empty") {
        val hubMessages = Channel<String>(Channel.UNLIMITED)
        val input = ScriptedInputSource(listOf("hello", "exit"))
        val turnController = TurnController(runtime, input, LiveRegion(StringBuilder()) { 80 }) {}
        val engine = TuiEngine(turnController, slashHandler, input, hubMessages)

        withTimeout(5000) { engine.run(AgentSession(id = "s1")) }
        verify(exactly = 1) { provider.stream(any()) }
    }
})
