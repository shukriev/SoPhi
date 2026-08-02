package dev.sophi.core.agent.plan

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.SessionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class PlanRunnerConfig(
    val model: String,
    val maxTokens: Int = 4096,
    val escalationModel: String? = null,
    val escalationThreshold: Double = 0.5,
    val maxReplans: Int = 3,
    val maxStepExecutions: Int = 20,
    val allowParallelSteps: Boolean = false
)

/**
 * Sits above AgentLoop exactly where sophi-schedule's GoalRunner used to sit — one
 * agentLoop.turn() call per step, each in its own isolated child session (same isolation
 * pattern as SubagentTool/ADR-007), so steps scheduled to run concurrently never mutate a
 * shared AgentSession.
 */
class PlanRunner(
    private val agentLoop: AgentLoop,
    private val sessionManager: SessionManager,
    private val provider: LLMProvider,
    private val planner: Planner,
    private val critic: StepCritic,
    private val config: PlanRunnerConfig,
    private val judgeModel: String = config.model,
    private val shellRunner: (String) -> Int = { cmd -> ProcessBuilder("sh", "-c", cmd).start().waitFor() },
    private val onPlanComplete: suspend (PlanOutcome) -> Unit = {}
) {
    suspend fun run(
        parentSessionId: String,
        goalPrompt: String,
        stopCondition: StopCondition,
        context: List<String> = emptyList()
    ): PlanOutcome {
        var plan = planner.plan(goalPrompt, context)
        var stepExecutions = 0
        val replans = mutableListOf<ReplanEvent>()
        val stepOutputs = mutableMapOf<String, String>()
        var lastOutput = ""

        while (true) {
            val wave = executeReadyWave(plan, parentSessionId, stepOutputs)
            plan = wave.plan
            stepExecutions += wave.executedCount
            wave.plan.steps.forEach { step -> stepOutputs[step.id]?.let { lastOutput = it } }

            val failedStep = plan.steps.firstOrNull { it.status == StepStatus.Failed }
            val stuck = wave.executedCount == 0 && failedStep == null && plan.steps.any { it.status != StepStatus.Done }

            if (failedStep != null || stuck) {
                if (replans.size >= config.maxReplans || stepExecutions >= config.maxStepExecutions) {
                    return finish(PlanFinalStatus.Exhausted, lastOutput, plan, replans)
                }
                val anchor = failedStep ?: plan.steps.first { it.status != StepStatus.Done }
                val reason = failedStep?.let { "step ${it.id} failed" }
                    ?: "no step could make progress (unresolved dependency)"
                plan = planner.replan(plan, anchor.id, reason, context)
                replans.add(ReplanEvent(anchor.id, reason, plan.version))
                continue
            }

            if (plan.steps.any { it.status != StepStatus.Done }) continue

            if (checkStopCondition(stopCondition, goalPrompt, lastOutput)) {
                return finish(PlanFinalStatus.Met, lastOutput, plan, replans)
            }
            if (replans.size >= config.maxReplans || stepExecutions >= config.maxStepExecutions) {
                return finish(PlanFinalStatus.Exhausted, lastOutput, plan, replans)
            }
            val anchorId = plan.steps.last().id
            val reason = "stop condition not met after all steps completed"
            plan = planner.replan(plan, anchorId, reason, context)
            replans.add(ReplanEvent(anchorId, reason, plan.version))
        }
    }

    private suspend fun finish(status: PlanFinalStatus, lastOutput: String, plan: Plan, replans: List<ReplanEvent>): PlanOutcome {
        val outcome = PlanOutcome(status, lastOutput, plan.version, plan.steps.size, replans)
        onPlanComplete(outcome)
        return outcome
    }

    private data class WaveResult(val plan: Plan, val executedCount: Int)

    private suspend fun executeReadyWave(
        plan: Plan, parentSessionId: String, stepOutputs: MutableMap<String, String>
    ): WaveResult {
        val ready = plan.steps.filter { step ->
            step.status == StepStatus.Pending && step.dependsOn.all { depId ->
                plan.steps.find { it.id == depId }?.status == StepStatus.Done
            }
        }
        if (ready.isEmpty()) return WaveResult(plan, 0)

        val results = if (config.allowParallelSteps) {
            coroutineScope { ready.map { step -> async { runStep(step, plan, parentSessionId, stepOutputs) } }.awaitAll() }
        } else {
            ready.map { step -> runStep(step, plan, parentSessionId, stepOutputs) }
        }

        val byId = results.toMap()
        val updatedSteps = plan.steps.map { byId[it.id] ?: it }
        return WaveResult(plan.copy(steps = updatedSteps), results.size)
    }

    private suspend fun runStep(
        step: PlanStep, plan: Plan, parentSessionId: String, stepOutputs: MutableMap<String, String>
    ): Pair<String, PlanStep> {
        val instruction = withDependencyContext(step, stepOutputs)
        val (output, ok) = executeOnce(plan, step, instruction, step.modelOverride ?: config.model, parentSessionId)
        if (!ok) {
            stepOutputs[step.id] = output
            return step.id to step.copy(status = StepStatus.Failed, confidence = 0.0)
        }

        var confidence = critic.judge(step, output)
        var finalOutput = output
        var usedModel = step.modelOverride

        if (confidence < config.escalationThreshold && step.modelOverride == null && config.escalationModel != null) {
            val (escalatedOutput, escalatedOk) =
                executeOnce(plan, step, instruction, config.escalationModel, parentSessionId)
            if (escalatedOk) {
                confidence = critic.judge(step, escalatedOutput)
                finalOutput = escalatedOutput
                usedModel = config.escalationModel
            }
        }

        stepOutputs[step.id] = finalOutput
        val status = if (confidence >= config.escalationThreshold) StepStatus.Done else StepStatus.Failed
        return step.id to step.copy(status = status, confidence = confidence, modelOverride = usedModel)
    }

    private fun withDependencyContext(step: PlanStep, stepOutputs: Map<String, String>): String {
        val depContext = step.dependsOn.mapNotNull { depId -> stepOutputs[depId]?.let { "Result of step $depId:\n$it" } }
        return if (depContext.isEmpty()) step.instruction
        else depContext.joinToString("\n\n") + "\n\nNow: ${step.instruction}"
    }

    private suspend fun executeOnce(
        plan: Plan, step: PlanStep, instruction: String, model: String, parentSessionId: String
    ): Pair<String, Boolean> {
        val stepSession = sessionManager.create(title = "plan:${plan.id}:step:${step.id}", parentSessionId = parentSessionId)
        val agentConfig = AgentConfig(model = model, maxTokens = config.maxTokens)
        return try {
            val result = agentLoop.turn(stepSession, instruction, agentConfig)
            (result.tip?.content ?: "") to true
        } catch (e: Exception) {
            (e.message ?: "step execution failed") to false
        }
    }

    private suspend fun checkStopCondition(stopCondition: StopCondition, goalPrompt: String, lastOutput: String): Boolean =
        when (stopCondition) {
            is StopCondition.LlmJudged -> judge(goalPrompt, lastOutput)
            is StopCondition.ShellCheck -> {
                val exit = shellRunner(stopCondition.command)
                if (stopCondition.expectExitZero) exit == 0 else exit != 0
            }
        }

    private suspend fun judge(goal: String, output: String): Boolean {
        val prompt = "Goal: $goal\n\nLatest agent output:\n$output\n\n" +
            "Has the goal been fully achieved? Answer with exactly one word: YES or NO."
        val response = provider.complete(
            CompletionRequest(
                messages = listOf(Message(MessageRole.USER, prompt)),
                model = judgeModel, maxTokens = 8, temperature = 0.0
            )
        )
        return (response as? LLMResponse.Text)?.content?.trim()?.uppercase()?.startsWith("YES") ?: false
    }
}
