package dev.sophi.schedule.engine

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession
import dev.sophi.schedule.model.StopCondition

class GoalRunner(
    private val agentLoop: AgentLoop,
    private val provider: LLMProvider,
    private val judgeModel: String,
    private val shellRunner: (String) -> Int = { cmd -> ProcessBuilder("sh", "-c", cmd).start().waitFor() }
) {
    data class Result(val met: Boolean, val iterations: Int, val lastOutput: String)

    suspend fun run(
        session: AgentSession,
        goalPrompt: String,
        config: AgentConfig,
        stopCondition: StopCondition,
        maxIterations: Int
    ): Result {
        var current = session
        var lastOutput = ""
        for (iteration in 1..maxIterations) {
            val userInput = if (iteration == 1) {
                goalPrompt
            } else {
                "Continue working towards the goal. It has not been met yet."
            }
            current = agentLoop.turn(current, userInput, config)
            lastOutput = current.tip?.content ?: ""

            val met = when (stopCondition) {
                is StopCondition.LlmJudged -> judge(goalPrompt, lastOutput)
                is StopCondition.ShellCheck -> {
                    val exit = shellRunner(stopCondition.command)
                    if (stopCondition.expectExitZero) exit == 0 else exit != 0
                }
            }
            if (met) return Result(met = true, iterations = iteration, lastOutput = lastOutput)
        }
        return Result(met = false, iterations = maxIterations, lastOutput = lastOutput)
    }

    private suspend fun judge(goal: String, output: String): Boolean {
        val prompt = "Goal: $goal\n\nLatest agent output:\n$output\n\n" +
            "Has the goal been fully achieved? Answer with exactly one word: YES or NO."
        val response = provider.complete(
            CompletionRequest(
                messages = listOf(Message(MessageRole.USER, prompt)),
                model = judgeModel,
                maxTokens = 8,
                temperature = 0.0
            )
        )
        return (response as? LLMResponse.Text)?.content?.trim()?.uppercase()?.startsWith("YES") ?: false
    }
}
