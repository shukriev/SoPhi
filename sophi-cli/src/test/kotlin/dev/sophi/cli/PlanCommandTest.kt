package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

private const val TEST_CONTEXT_WINDOW = 100_000

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
