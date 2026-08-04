package dev.sophi.core.agent.plan

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

class DecomposeGoalToolTest : FunSpec({
    fun tool(
        provider: LLMProvider,
        registry: ToolRegistry = ToolRegistry(),
        depth: Int = 0
    ) = DecomposeGoalTool(
        provider = provider,
        fullRegistry = registry,
        sessionManager = FileSessionManager(createTempDirectory("decompose-goal-tool-test")),
        parentSessionId = "parent",
        parentConfig = AgentConfig(model = "test-model"),
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

        val result = runBlocking { tool(provider).execute("""{"goal":"ship the release"}""") }

        result shouldContain "branch created"
        result shouldContain "[s1]"
        result shouldContain "Done"
    }

    test("an exhausted goal reports a handled error rather than throwing") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } throws RuntimeException("nope")
        coEvery { provider.complete(any()) } returns LLMResponse.Text(
            """{"steps":[{"id":"s1","instruction":"impossible"}]}""", TokenUsage(1, 1))

        val result = runBlocking { tool(provider).execute("""{"goal":"do the impossible"}""") }

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

        runBlocking { tool(provider, registry, depth = 1).execute("""{"goal":"g"}""") }

        registry.getOrNull("decompose_goal") shouldBe null
    }

    test("the tool advertises itself with a stable name and a goal parameter") {
        val t = tool(mockk<LLMProvider>())
        t.name shouldBe "decompose_goal"
        t.parametersJson shouldContain "\"goal\""
    }
})
