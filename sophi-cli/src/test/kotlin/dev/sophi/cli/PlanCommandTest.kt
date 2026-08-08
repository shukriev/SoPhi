package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

private const val TEST_CONTEXT_WINDOW = 100_000

/**
 * Mirrors [SophiTerminal]'s real contract: [awaitYesNo] never reads anything itself — it just
 * registers a pending answer that only a concurrently-running [awaitControlKeys] loop can ever
 * complete. If a caller invokes [awaitYesNo] without something else driving [awaitControlKeys]
 * at the same time, it hangs forever, exactly like the real terminal does.
 */
private class ConfirmationRelayInputSource : InputSource {
    private val pending = AtomicReference<CompletableDeferred<Boolean>?>(null)
    override suspend fun readLine(): String? = null
    override suspend fun awaitEsc() {}
    override suspend fun awaitYesNo(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending.set(deferred)
        return deferred.await()
    }
    override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            delay(10)
            pending.getAndSet(null)?.complete(true)
        }
    }
}

private class DestructiveTool : Tool {
    override val name = "dangerous_tool"
    override val description = "a destructive tool"
    override val parametersJson = "{}"
    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
    override suspend fun execute(argumentsJson: String) = "boom"
}

class PlanCommandTest : FunSpec({
    val output = mutableListOf<String>()

    beforeTest { output.clear() }

    fun command(provider: LLMProvider) = PlanCommand(
        provider = provider,
        registry = ToolRegistry(),
        sessionManager = FileSessionManager(tempdir().toPath()),
        config = AgentConfig(model = "test-model"),
        contextWindowTokens = TEST_CONTEXT_WINDOW,
        confirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
        planLog = null
    ) { output.add(it) }

    test("a successful run prints the plan id and the per-step summary") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("all done"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )

        runBlocking { command(provider).run("ship the release", AgentSession(id = "s1")) }

        output.joinToString("\n") shouldContain "plan_"
        output.joinToString("\n") shouldContain "[s1]"
        output.joinToString("\n") shouldContain "all done"
    }

    test("the run's summary is appended to the session so the next turn can see it") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("all done"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )
        val session = AgentSession(id = "s1")

        val returned = runBlocking { command(provider).run("ship the release", session) }

        val appended = returned.branch().filter { it.role == EntryRole.ASSISTANT }
        appended shouldHaveSize 1
        appended.single().content shouldContain "all done"
    }

    test("a step's tool call is observable through PlanCommand's onEvent bridge") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(dev.sophi.ai.api.StreamEvent.ToolCallsReady(
                listOf(dev.sophi.ai.api.ToolCall("c1", "some_tool", "{}"))
            )),
            flowOf(StreamEvent.Content("done"))
        )
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"call some_tool"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )
        val events = mutableListOf<dev.sophi.core.agent.TurnEvent>()
        val cmd = PlanCommand(
            provider = provider, registry = ToolRegistry(),
            sessionManager = FileSessionManager(tempdir().toPath()),
            config = AgentConfig(model = "test-model"), contextWindowTokens = TEST_CONTEXT_WINDOW,
            confirmationPolicy = ConfirmationPolicy.ALLOW_ALL, planLog = null,
            onEvent = { events.add(it) }, echo = { output.add(it) }
        )

        runBlocking { cmd.run("call some_tool", AgentSession(id = "s1")) }

        events.filterIsInstance<dev.sophi.core.agent.TurnEvent.ToolCallStarted>().map { it.name } shouldContain "some_tool"
    }

    test("a step boundary is echoed live, ahead of the final summary") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("all done"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )
        val cmd = PlanCommand(
            provider = provider, registry = ToolRegistry(),
            sessionManager = FileSessionManager(tempdir().toPath()),
            config = AgentConfig(model = "test-model"), contextWindowTokens = TEST_CONTEXT_WINDOW,
            confirmationPolicy = ConfirmationPolicy.ALLOW_ALL, planLog = null,
            liveRegion = LiveRegion(StringBuilder()) { 80 }, echo = { output.add(it) }
        )

        runBlocking { cmd.run("ship the release", AgentSession(id = "s1")) }

        output.size shouldBe 3
        output[0] shouldContain "▶ [s1] do it"
        output[1] shouldContain "[s1] Done"
        output[2] shouldContain "Goal: ship the release"
    }

    test("a plan step's destructive tool call can actually be confirmed instead of hanging forever") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "dangerous_tool", "{}")))),
            flowOf(StreamEvent.Content("done"))
        )
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"call dangerous_tool"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )
        val registry = ToolRegistry().register(DestructiveTool())
        val input = ConfirmationRelayInputSource()
        val confirmationPolicy = TerminalConfirmationPolicy(mockk(relaxed = true), input)
        val cmd = PlanCommand(
            provider = provider, registry = registry,
            sessionManager = FileSessionManager(tempdir().toPath()),
            config = AgentConfig(model = "test-model"), contextWindowTokens = TEST_CONTEXT_WINDOW,
            confirmationPolicy = confirmationPolicy, planLog = null,
            input = input, echo = { output.add(it) }
        )

        runBlocking {
            withTimeout(2000) { cmd.run("call dangerous_tool", AgentSession(id = "s1")) }
        }

        output.joinToString("\n") shouldContain "boom"
    }

    test("/plan with no goal prints usage and leaves the session untouched") {
        val handler = SlashHandler(
            mockk(relaxed = true), null, AgentConfig(model = "test-model"),
            toolRegistry = ToolRegistry(), provider = mockk<LLMProvider>()
        ) { output.add(it) }
        val session = AgentSession(id = "s1")

        val returned = runBlocking { handler.handle("/plan", session) }

        output.single() shouldBe "Usage: /plan <goal>"
        returned.branch() shouldHaveSize 0
    }

    test("/plan degrades gracefully when no tool registry is wired") {
        val handler = SlashHandler(
            mockk(relaxed = true), null, AgentConfig(model = "test-model"),
            provider = mockk<LLMProvider>()
        ) { output.add(it) }

        runBlocking { handler.handle("/plan do a thing", AgentSession(id = "s1")) }

        output.single() shouldBe "Planning is not available (no tools configured)."
    }

    test("/plan degrades gracefully when no context window is configured") {
        val handler = SlashHandler(
            mockk(relaxed = true), null, AgentConfig(model = "test-model"),
            toolRegistry = ToolRegistry(), provider = mockk<LLMProvider>()
        ) { output.add(it) }

        runBlocking { handler.handle("/plan do a thing", AgentSession(id = "s1")) }

        output.single() shouldBe "Planning is not available (no tools configured)."
    }
})
