package dev.sophi.cli

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin
import dev.sophi.learning.JsonlLog
import dev.sophi.sdk.Sophi
import dev.sophi.sdk.SophiRuntime
import dev.sophi.learning.LearningConfig
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

private const val TEST_CONTEXT_WINDOW = 100_000

private fun keys(text: String) = text.map { TurnKey.Text(it) }.toTypedArray()

/** Waits until the live region has drawn [text], i.e. the typed keys reached the editor. */
private suspend fun StringBuilder.awaitDrawn(text: String) = withTimeout(5000) {
    while (text !in this@awaitDrawn.toString()) delay(10)
}

class TurnControllerTest : FunSpec({
    val provider = mockk<LLMProvider>()

    // Built through the public builder rather than by constructing a SophiRuntime directly:
    // its constructor is internal to sophi-sdk, and going through Sophi.runtime {} is exactly
    // the path sophi-cli itself now takes. ALLOW_ALL is explicit because RuntimeBuilder defaults
    // to DENY_ALL where a bare AgentLoop defaulted to ALLOW_ALL.
    fun runtimeFor(
        registry: ToolRegistry = ToolRegistry(),
        policy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
        extraPlugin: SophiPlugin? = null,
        learningConfig: LearningConfig? = null
    ): SophiRuntime = Sophi.runtime {
        this.provider = provider
        model = "test-model"
        contextWindowTokens(TEST_CONTEXT_WINDOW)
        sessionsDir = tempdir().toPath()
        toolRegistry(registry)
        confirmationPolicy(policy)
        extraPlugin?.let { plugin(it) }
        learningConfig?.let { learning(it) }
    }

    beforeTest { clearMocks(provider) }

    test("runTurn() streams tokens and renders the final response to output") {
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("Hello"), StreamEvent.Content(" "), StreamEvent.Content("World"))
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(runtimeFor(), input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }

        controller.runTurn(AgentSession(id = "s1"), "hi")

        rendered shouldBe listOf(ResponseRenderer.renderText("Hello World"))
    }

    test("runTurn() keeps reasoning visible in the output after the turn completes") {
        every { provider.stream(any()) } returns flowOf(StreamEvent.Reasoning("thinking..."), StreamEvent.Content("done"))
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val controller = TurnController(runtimeFor(), input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }

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
        val controller = TurnController(runtimeFor(registry = toolRegistry), input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }

        controller.runTurn(AgentSession(id = "s1"), "ping")

        rendered shouldBe listOf(
            ResponseRenderer.renderToolCall("ping", "{}", "pong"),
            ResponseRenderer.renderText("done")
        )
    }

    test("a turn that changed the todo list prints it under the reply") {
        val toolRegistry = ToolRegistry().register(dev.sophi.core.tools.TodoTool())
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1) flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "todo", """{"items":[{"text":"a","status":"in_progress"}]}"""))))
            else flowOf(StreamEvent.Content("done"))
        }
        val rendered = mutableListOf<String>()
        val controller = TurnController(runtimeFor(registry = toolRegistry), ScriptedInputSource(emptyList()), LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }
        controller.runTurn(AgentSession(id = "s1"), "plan")
        rendered.last() shouldBe com.github.ajalt.mordant.rendering.TextColors.gray("[>] a")
    }

    // The CLI no longer dispatches AFTER_TURN itself — SophiRuntime.streamTurn does — so this
    // now verifies the real path: a plugin registered on the runtime records the turn.
    test("a turn dispatches AFTER_TURN so a runtime-registered plugin records it") {
        val home = tempdir().toPath()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("Hello"))
        val input = ScriptedInputSource(emptyList())
        val rendered = mutableListOf<String>()
        val runtime = runtimeFor(learningConfig = LearningConfig(home = home, scope = "/proj"))
        val controller = TurnController(runtime, input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }

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
        val controller = TurnController(runtimeFor(), input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }
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
            runtimeFor(registry = toolRegistry, policy = confirmationPolicy),
            input, LiveRegion(recordingAppendable) { 80 }
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
        val controller = TurnController(runtimeFor(), input, LiveRegion(StringBuilder()) { 80 }) { rendered.add(it) }
        val session = AgentSession(id = "s1")

        val result = controller.runTurn(session, "hi")

        result shouldBe session
        rendered shouldBe listOf(ResponseRenderer.renderText("") + " [error: stream error]")
    }

    test("typing Enter during a turn steers it and prints the steer") {
        val toolStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val toolRegistry = ToolRegistry()
        toolRegistry.register(object : Tool {
            override val name = "wait"
            override val description = "Waits"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String): String { toolStarted.complete(Unit); gate.await(); return "ok" }
        })
        val requests = mutableListOf<CompletionRequest>()
        every { provider.stream(any()) } answers {
            requests += firstArg<CompletionRequest>()
            if (requests.size == 1) flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "wait", "{}"))))
            else flowOf(StreamEvent.Content("done"))
        }
        val input = ScriptedInputSource(emptyList())
        val screen = StringBuilder()
        val rendered = mutableListOf<String>()
        val controller = TurnController(runtimeFor(registry = toolRegistry), input, LiveRegion(screen) { 80 }) { rendered.add(it) }

        coroutineScope {
            val turn = async { controller.runTurn(AgentSession(id = "s1"), "start") }
            toolStarted.await()
            input.type(*keys("use X"), TurnKey.Enter)
            screen.awaitDrawn("↳ use X")
            gate.complete(Unit)
            turn.await()
        }

        requests.last().messages.last() shouldBe Message(MessageRole.USER, "use X")
        rendered shouldBe listOf(
            ResponseRenderer.renderToolCall("wait", "{}", "ok"),
            "You: use X",
            ResponseRenderer.renderText("done")
        )
    }

    test("Alt+Enter runs the follow-up after the answer; both answers are printed separately") {
        val input = ScriptedInputSource(emptyList())
        val screen = StringBuilder()
        var round = 0
        every { provider.stream(any()) } answers {
            round++
            if (round == 1) flow {
                emit(StreamEvent.Content("first"))
                input.type(*keys("more"), TurnKey.AltEnter)
                screen.awaitDrawn("⏎ more (next turn)")
            } else flowOf(StreamEvent.Content("second"))
        }
        val rendered = mutableListOf<String>()
        val controller = TurnController(runtimeFor(), input, LiveRegion(screen) { 80 }) { rendered.add(it) }

        controller.runTurn(AgentSession(id = "s1"), "hi")

        rendered shouldBe listOf(
            ResponseRenderer.renderText("first"),
            "You: more",
            ResponseRenderer.renderText("second")
        )
    }

    test("ESC returns unsent text to the editor") {
        val input = ScriptedInputSource(emptyList())
        val screen = StringBuilder()
        every { provider.stream(any()) } returns flow {
            emit(StreamEvent.Content("partial"))
            input.type(*keys("steer text"), TurnKey.Enter, *keys("follow text"), TurnKey.AltEnter, *keys("half"))
            screen.awaitDrawn("› half")
            input.signalEsc()
            delay(Long.MAX_VALUE)
        }
        val controller = TurnController(runtimeFor(), input, LiveRegion(screen) { 80 }) {}

        controller.runTurn(AgentSession(id = "s1"), "hi")

        controller.takeDraft() shouldBe "steer text\nfollow text\nhalf"
        controller.takeDraft() shouldBe ""
    }

    // ESC while a follow-up's BEFORE_TURN hook runs: the runtime puts it back in the inbox as the
    // cancelled turn settles, so the draft must be taken after that, not right after the select.
    test("ESC before a follow-up's turn starts returns it to the editor") {
        val input = ScriptedInputSource(emptyList())
        val screen = StringBuilder()
        val stall = object : SophiPlugin {
            override val name = "stall"
            override fun hooks() = listOf(object : AgentHook {
                override val point = HookPoint.BEFORE_TURN
                override suspend fun invoke(context: HookContext) {
                    if (context.userInput == "more") { input.signalEsc(); awaitCancellation() }
                }
            })
        }
        every { provider.stream(any()) } returns flow {
            emit(StreamEvent.Content("first"))
            input.type(*keys("more"), TurnKey.AltEnter)
            screen.awaitDrawn("⏎ more (next turn)")
        }
        val controller = TurnController(runtimeFor(extraPlugin = stall), input, LiveRegion(screen) { 80 }) {}

        controller.runTurn(AgentSession(id = "s1"), "hi")

        controller.takeDraft() shouldBe "more"
    }
})
