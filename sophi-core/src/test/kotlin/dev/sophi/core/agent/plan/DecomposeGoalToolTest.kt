package dev.sophi.core.agent.plan

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.ToolRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

private const val TEST_CONTEXT_WINDOW = 100_000

class DecomposeGoalToolTest : FunSpec({
    fun tool(
        provider: LLMProvider,
        registry: ToolRegistry = ToolRegistry(),
        depth: Int = 0
    ) = DecomposeGoalTool(
        provider = provider,
        fullRegistry = registry,
        sessionManager = FileSessionManager(createTempDirectory("decompose-goal-tool-test")),
        parentConfig = AgentConfig(model = "test-model"),
        contextWindowTokens = TEST_CONTEXT_WINDOW,
        depth = depth
    )

    test("a met goal returns the final output and a per-step summary") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("branch created"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"cut a release branch"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )

        val result = runBlocking(SessionIdContext("parent")) { tool(provider).execute("""{"goal":"ship the release"}""") }

        result shouldContain "branch created"
        result shouldContain "[s1]"
        result shouldContain "Done"
    }

    test("an exhausted goal reports a handled error rather than throwing") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } throws RuntimeException("nope")
        coEvery { provider.complete(any()) } returns LLMResponse.Text(
            """{"steps":[{"id":"s1","instruction":"impossible"}]}""", TokenUsage(1, 1))

        val result = runBlocking(SessionIdContext("parent")) { tool(provider).execute("""{"goal":"do the impossible"}""") }

        result.startsWith("Error: goal not met - ") shouldBe true
    }

    test("risk tier is derived from the declared expected_tools, like delegate_to_subagent") {
        val provider = mockk<LLMProvider>()
        val registry = ToolRegistry().register(BashTool())
        val t = tool(provider, registry)

        t.riskLevel("""{"goal":"g","expected_tools":["bash"]}""") shouldBe RiskLevel.DESTRUCTIVE
        t.riskLevel("""{"goal":"g"}""") shouldBe RiskLevel.SAFE
    }

    test("at the tool depth cap the nested run cannot call decompose_goal again") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )
        val registry = ToolRegistry()

        runBlocking(SessionIdContext("parent")) { tool(provider, registry, depth = 1).execute("""{"goal":"g"}""") }

        registry.getOrNull("decompose_goal") shouldBe null
    }

    test("expected_tools only grants SAFE tools — a DESTRUCTIVE one still needs confirmation") {
        val provider = mockk<LLMProvider>()
        val workDir = createTempDirectory("decompose-goal-grants-workdir")
        val marker = workDir.resolve("marker.txt")
        val registry = ToolRegistry().register(BashTool(workDir))
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "bash", """{"command":"touch marker.txt"}""")))),
            flowOf(StreamEvent.Content("done"))
        )
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"touch a marker file"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )
        val t = DecomposeGoalTool(
            provider = provider,
            fullRegistry = registry,
            sessionManager = FileSessionManager(createTempDirectory("decompose-goal-grants-sessions")),
            parentConfig = AgentConfig(model = "test-model"),
            contextWindowTokens = TEST_CONTEXT_WINDOW,
            confirmationPolicy = ConfirmationPolicy.DENY_ALL
        )

        runBlocking(SessionIdContext("parent")) { t.execute("""{"goal":"g","expected_tools":["bash"]}""") }

        marker.exists() shouldBe false
    }

    test("the tool advertises itself with a stable name and a goal parameter") {
        val t = tool(mockk<LLMProvider>())
        t.name shouldBe "decompose_goal"
        t.parametersJson shouldContain "\"goal\""
    }

    test("execute() throws when SessionIdContext is absent, without calling the LLM") {
        val provider = mockk<LLMProvider>()
        val t = DecomposeGoalTool(
            provider = provider,
            fullRegistry = ToolRegistry(),
            sessionManager = FileSessionManager(createTempDirectory("decompose-goal-tool-test")),
            parentConfig = AgentConfig(model = "test-model"),
            contextWindowTokens = TEST_CONTEXT_WINDOW
        )

        shouldThrow<IllegalStateException> { runBlocking { t.execute("""{"goal":"ship the release"}""") } }

        coVerify(exactly = 0) { provider.complete(any()) }
    }
})
