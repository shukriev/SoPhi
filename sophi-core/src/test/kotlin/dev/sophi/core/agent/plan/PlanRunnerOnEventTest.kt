package dev.sophi.core.agent.plan

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

class PlanRunnerOnEventTest : FunSpec({
    test("a step's tool call is observable through the PlanRunner-level onEvent bridge") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", "some_tool", "{}")))),
            flowOf(StreamEvent.Content("done"))
        )
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"call some_tool"}]}""", TokenUsage(1, 1)),
            LLMResponse.Text("1.0", TokenUsage(1, 1)),
            LLMResponse.Text("YES", TokenUsage(1, 1))
        )
        val sessionManager = FileSessionManager(createTempDirectory("plan-runner-onevent-test"))
        val agentLoop = AgentLoop(
            provider, ToolRegistry(), sessionManager,
            confirmationPolicy = ConfirmationPolicy.ALLOW_ALL, contextWindowTokens = TEST_CONTEXT_WINDOW
        )
        val events = mutableListOf<TurnEvent>()
        val runner = PlanRunner(
            agentLoop = agentLoop, sessionManager = sessionManager, provider = provider,
            planner = LlmPlanner(provider, "test-model"), critic = LlmStepCritic(provider, "test-model"),
            config = PlanRunnerConfig(model = "test-model"),
            onEvent = { events.add(it) }
        )

        runBlocking { runner.run("parent-1", "call some_tool", StopCondition.LlmJudged) }

        events.filterIsInstance<TurnEvent.ToolCallStarted>().map { it.name } shouldContain "some_tool"
        events.filterIsInstance<TurnEvent.ToolCallFinished>().map { it.name } shouldContain "some_tool"
    }
})
