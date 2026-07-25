package dev.sophi.schedule.engine

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.schedule.model.StopCondition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlin.io.path.createTempDirectory

class GoalRunnerTest : FunSpec({
    fun agentLoop(provider: LLMProvider) =
        AgentLoop(provider, ToolRegistry(), FileSessionManager(createTempDirectory("goal-runner-test")))

    test("LlmJudged stops as soon as the judge answers YES") {
        val provider = mockk<LLMProvider>()
        var call = 0
        coEvery { provider.complete(any()) } answers {
            call++
            LLMResponse.Text(if (call >= 4) "YES" else "NO", TokenUsage(1, 1))
        }
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("working on it"))
        val runner = GoalRunner(agentLoop(provider), provider, judgeModel = "judge-model")
        val session = AgentSession(id = "s1")
        val result = kotlinx.coroutines.runBlocking {
            runner.run(session, "reach the goal", AgentConfig(model = "m"), StopCondition.LlmJudged, maxIterations = 5)
        }
        result.met shouldBe true
        (result.iterations <= 5) shouldBe true
    }

    test("LlmJudged exhausts maxIterations when the judge never says YES") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("NO", TokenUsage(1, 1))
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("still working"))
        val runner = GoalRunner(agentLoop(provider), provider, judgeModel = "judge-model")
        val session = AgentSession(id = "s2")
        val result = kotlinx.coroutines.runBlocking {
            runner.run(session, "reach the goal", AgentConfig(model = "m"), StopCondition.LlmJudged, maxIterations = 3)
        }
        result.met shouldBe false
        result.iterations shouldBe 3
    }

    test("ShellCheck stops when the command exits zero") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("fixed it"))
        var calls = 0
        val runner = GoalRunner(agentLoop(provider), provider, judgeModel = "judge-model",
            shellRunner = { calls++; if (calls >= 2) 0 else 1 })
        val session = AgentSession(id = "s3")
        val result = kotlinx.coroutines.runBlocking {
            runner.run(session, "fix the tests", AgentConfig(model = "m"),
                StopCondition.ShellCheck("./run.sh"), maxIterations = 5)
        }
        result.met shouldBe true
        result.iterations shouldBe 2
    }

    test("ShellCheck with expectExitZero=false stops on a non-zero exit") {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returns flowOf(StreamEvent.Content("done"))
        val runner = GoalRunner(agentLoop(provider), provider, judgeModel = "judge-model",
            shellRunner = { 1 })
        val session = AgentSession(id = "s4")
        val result = kotlinx.coroutines.runBlocking {
            runner.run(session, "goal", AgentConfig(model = "m"),
                StopCondition.ShellCheck("./run.sh", expectExitZero = false), maxIterations = 5)
        }
        result.met shouldBe true
        result.iterations shouldBe 1
    }
})
