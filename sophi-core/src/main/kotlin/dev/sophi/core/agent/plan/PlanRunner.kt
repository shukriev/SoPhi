package dev.sophi.core.agent.plan

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tree-wide cap on agent turns. Atomic because allowParallelSteps runs steps under
 * async/awaitAll — a per-plan counter would let a two-level tree execute
 * maxStepExecutions^2 turns.
 */
internal class RunBudget(private val max: Int) {
    private val consumed = AtomicInteger(0)
    fun tryConsume(): Boolean = consumed.getAndUpdate { if (it >= max) it else it + 1 } < max
    fun hasRoom(): Boolean = consumed.get() < max
    fun used(): Int = consumed.get()
}

/**
 * Live-progress counterpart to the batch results in PlanOutcome — fired as a run happens rather
 * than returned once it finishes, so a caller (the CLI's /plan) can show something between the
 * long silences of chained agentLoop.turn calls instead of only a summary at the very end.
 */
sealed class PlanProgressEvent {
    data class StepStarted(val planId: String, val step: PlanStep) : PlanProgressEvent()
    data class StepFinished(val planId: String, val step: PlanStep) : PlanProgressEvent()
    data class Replanned(val planId: String, val stepId: String, val reason: String) : PlanProgressEvent()
    data class Decomposed(
        val stepId: String, val childPlanId: String, val trigger: DecompositionTrigger
    ) : PlanProgressEvent()
}

data class PlanRunnerConfig(
    val model: String,
    val maxTokens: Int = 4096,
    val escalationModel: String? = null,
    val escalationThreshold: Double = 0.5,
    val maxReplans: Int = 3,
    val maxStepExecutions: Int = 20,
    val allowParallelSteps: Boolean = false,
    /** 0 keeps plans flat; 2 allows a root plan plus two levels of sub-plans. */
    val maxPlanDepth: Int = 2
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
    private val onPlanComplete: suspend (PlanOutcome) -> Unit = {},
    private val planLog: PlanLog? = null,
    private val onEvent: suspend (TurnEvent) -> Unit = {},
    private val onProgress: suspend (PlanProgressEvent) -> Unit = {}
) {
    suspend fun run(
        parentSessionId: String,
        goalPrompt: String,
        stopCondition: StopCondition,
        context: List<String> = emptyList()
    ): PlanOutcome = runPlan(
        parentSessionId = parentSessionId,
        goalPrompt = goalPrompt,
        stopCondition = stopCondition,
        context = context,
        depth = 0,
        parentStepId = null,
        budget = RunBudget(config.maxStepExecutions),
        decompositions = mutableListOf()
    )

    private suspend fun runPlan(
        parentSessionId: String,
        goalPrompt: String,
        stopCondition: StopCondition,
        context: List<String>,
        depth: Int,
        parentStepId: String?,
        budget: RunBudget,
        decompositions: MutableList<DecompositionEvent>
    ): PlanOutcome {
        var plan = planner.plan(goalPrompt, context).copy(parentStepId = parentStepId, depth = depth)
        planLog?.append(plan)
        val replans = mutableListOf<ReplanEvent>()
        val stepOutputs = mutableMapOf<String, String>()
        var lastOutput = ""

        while (true) {
            val wave = executeReadyWave(plan, parentSessionId, stepOutputs, depth, budget, decompositions)
            plan = wave.plan
            wave.plan.steps.forEach { step -> stepOutputs[step.id]?.let { lastOutput = it } }

            val failedStep = plan.steps.firstOrNull { it.status == StepStatus.Failed }
            val stuck = wave.executedCount == 0 && failedStep == null && plan.steps.any { it.status != StepStatus.Done }

            if (failedStep != null || stuck) {
                if (replans.size >= config.maxReplans || !budget.hasRoom()) {
                    return finish(PlanFinalStatus.Exhausted, lastOutput, plan, replans, decompositions, depth)
                }
                if (failedStep != null && failedStep.childPlanId == null && canDecompose(depth, budget)) {
                    val (stepId, updated) = decomposeStep(
                        failedStep, plan, parentSessionId, stepOutputs, depth, budget, decompositions,
                        DecompositionTrigger.Failure
                    )
                    plan = plan.copy(steps = plan.steps.map { if (it.id == stepId) updated else it })
                    stepOutputs[stepId]?.let { lastOutput = it }
                    continue
                }

                val anchor = failedStep ?: plan.steps.first { it.status != StepStatus.Done }
                val reason = failedStep?.let {
                    if (it.childPlanId == null) "step ${it.id} failed"
                    else "step ${it.id}'s sub-plan (${it.childPlanId}) was exhausted"
                } ?: "no step could make progress (unresolved dependency)"
                plan = planner.replan(plan, anchor.id, reason, context)
                    .copy(parentStepId = parentStepId, depth = depth)
                planLog?.append(plan)
                replans.add(ReplanEvent(anchor.id, reason, plan.version))
                onProgress(PlanProgressEvent.Replanned(plan.id, anchor.id, reason))
                continue
            }

            if (plan.steps.any { it.status != StepStatus.Done }) continue

            if (checkStopCondition(stopCondition, goalPrompt, lastOutput)) {
                return finish(PlanFinalStatus.Met, lastOutput, plan, replans, decompositions, depth)
            }
            if (replans.size >= config.maxReplans || !budget.hasRoom()) {
                return finish(PlanFinalStatus.Exhausted, lastOutput, plan, replans, decompositions, depth)
            }
            val anchorId = plan.steps.last().id
            val reason = "stop condition not met after all steps completed"
            plan = planner.replan(plan, anchorId, reason, context).copy(parentStepId = parentStepId, depth = depth)
            planLog?.append(plan)
            replans.add(ReplanEvent(anchorId, reason, plan.version))
            onProgress(PlanProgressEvent.Replanned(plan.id, anchorId, reason))
        }
    }

    /**
     * onPlanComplete is the sophi-learning feedback seam (ADR-018) and must fire exactly once
     * per user-visible run — so sub-plans return their outcome without invoking it.
     */
    private suspend fun finish(
        status: PlanFinalStatus,
        lastOutput: String,
        plan: Plan,
        replans: List<ReplanEvent>,
        decompositions: List<DecompositionEvent>,
        depth: Int
    ): PlanOutcome {
        val outcome = PlanOutcome(
            finalStatus = status, finalOutput = lastOutput, planVersionCount = plan.version,
            totalSteps = plan.steps.size, replans = replans,
            decompositions = decompositions.toList(), planId = plan.id, finalSteps = plan.steps
        )
        if (depth == 0) onPlanComplete(outcome)
        return outcome
    }

    private fun canDecompose(depth: Int, budget: RunBudget): Boolean =
        depth < config.maxPlanDepth && budget.hasRoom()

    /**
     * The single seam both decomposition triggers use. Runs step.instruction as its own plan one
     * level down, in a session parented to this plan's session so the session tree mirrors the
     * plan tree. Always LlmJudged: a root ShellCheck asks "is the whole goal done", which is not
     * the question being asked of one step.
     */
    private suspend fun decomposeStep(
        step: PlanStep, plan: Plan, parentSessionId: String, stepOutputs: MutableMap<String, String>,
        depth: Int, budget: RunBudget, decompositions: MutableList<DecompositionEvent>,
        trigger: DecompositionTrigger
    ): Pair<String, PlanStep> {
        val subSession = sessionManager.create(
            title = "plan:${plan.id}:step:${step.id}:subplan", parentSessionId = parentSessionId
        )
        val outcome = runPlan(
            parentSessionId = subSession.id,
            goalPrompt = step.instruction,
            stopCondition = StopCondition.LlmJudged,
            context = listOf("Parent goal: ${plan.goalPrompt}", "This sub-plan must satisfy: ${step.instruction}"),
            depth = depth + 1,
            parentStepId = step.id,
            budget = budget,
            decompositions = decompositions
        )
        val met = outcome.finalStatus == PlanFinalStatus.Met
        decompositions.add(
            DecompositionEvent(step.id, outcome.planId, outcome.totalSteps, outcome.finalStatus, trigger)
        )
        onProgress(PlanProgressEvent.Decomposed(step.id, outcome.planId, trigger))
        stepOutputs[step.id] = outcome.finalOutput
        return step.id to step.copy(
            status = if (met) StepStatus.Done else StepStatus.Failed,
            confidence = if (met) 1.0 else 0.0,
            childPlanId = outcome.planId
        )
    }

    private data class WaveResult(val plan: Plan, val executedCount: Int)

    private suspend fun executeReadyWave(
        plan: Plan, parentSessionId: String, stepOutputs: MutableMap<String, String>,
        depth: Int, budget: RunBudget, decompositions: MutableList<DecompositionEvent>
    ): WaveResult {
        val ready = plan.steps.filter { step ->
            step.status == StepStatus.Pending && step.dependsOn.all { depId ->
                plan.steps.find { it.id == depId }?.status == StepStatus.Done
            }
        }
        if (ready.isEmpty()) return WaveResult(plan, 0)

        val results = if (config.allowParallelSteps) {
            coroutineScope {
                ready.map { step ->
                    async { runStep(step, plan, parentSessionId, stepOutputs, depth, budget, decompositions) }
                }.awaitAll()
            }
        } else {
            ready.map { step -> runStep(step, plan, parentSessionId, stepOutputs, depth, budget, decompositions) }
        }

        val byId = results.toMap()
        val updatedSteps = plan.steps.map { byId[it.id] ?: it }
        return WaveResult(plan.copy(steps = updatedSteps), results.size)
    }

    private suspend fun runStep(
        step: PlanStep, plan: Plan, parentSessionId: String, stepOutputs: MutableMap<String, String>,
        depth: Int, budget: RunBudget, decompositions: MutableList<DecompositionEvent>
    ): Pair<String, PlanStep> {
        onProgress(PlanProgressEvent.StepStarted(plan.id, step))
        val result = runStepBody(step, plan, parentSessionId, stepOutputs, depth, budget, decompositions)
        onProgress(PlanProgressEvent.StepFinished(plan.id, result.second))
        return result
    }

    private suspend fun runStepBody(
        step: PlanStep, plan: Plan, parentSessionId: String, stepOutputs: MutableMap<String, String>,
        depth: Int, budget: RunBudget, decompositions: MutableList<DecompositionEvent>
    ): Pair<String, PlanStep> {
        if (step.decompose && step.childPlanId == null && canDecompose(depth, budget)) {
            return decomposeStep(
                step, plan, parentSessionId, stepOutputs, depth, budget, decompositions,
                DecompositionTrigger.Declared
            )
        }

        val instruction = withDependencyContext(step, stepOutputs)
        val (output, ok) = executeOnce(plan, step, instruction, step.modelOverride ?: config.model, parentSessionId, budget)
        if (!ok) {
            stepOutputs[step.id] = output
            return step.id to step.copy(status = StepStatus.Failed, confidence = 0.0)
        }

        var confidence = critic.judge(step, output)
        var finalOutput = output
        var usedModel = step.modelOverride

        if (confidence < config.escalationThreshold && step.modelOverride == null && config.escalationModel != null) {
            val (escalatedOutput, escalatedOk) =
                executeOnce(plan, step, instruction, config.escalationModel, parentSessionId, budget)
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
        plan: Plan, step: PlanStep, instruction: String, model: String, parentSessionId: String, budget: RunBudget
    ): Pair<String, Boolean> {
        if (!budget.tryConsume()) return "step execution budget exhausted" to false
        val stepSession = sessionManager.create(title = "plan:${plan.id}:step:${step.id}", parentSessionId = parentSessionId)
        val agentConfig = AgentConfig(model = model, maxTokens = config.maxTokens)
        return try {
            val result = agentLoop.turn(stepSession, instruction, agentConfig, onEvent)
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

/**
 * The one construction path for a PlanRunner outside sophi-schedule. Both entry points
 * (DecomposeGoalTool and the CLI's /plan) call this so their wiring cannot drift apart.
 * ScheduleEngine deliberately keeps its own inline construction — it already builds a scoped
 * registry and a DENY_ALL policy for other reasons.
 */
fun buildPlanRunner(
    provider: LLMProvider,
    registry: ToolRegistry,
    sessionManager: SessionManager,
    config: PlanRunnerConfig,
    /** Total context window of `config.model`, in tokens — see AgentLoop.contextWindowTokens. */
    contextWindowTokens: Int,
    confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
    grants: Set<String> = emptySet(),
    planLog: PlanLog? = null,
    contextProvider: suspend (String) -> List<String> = { emptyList() },
    onPlanComplete: suspend (PlanOutcome) -> Unit = {},
    onEvent: suspend (TurnEvent) -> Unit = {},
    onProgress: suspend (PlanProgressEvent) -> Unit = {}
): PlanRunner {
    val loop = AgentLoop(
        provider, registry, sessionManager,
        confirmationPolicy = confirmationPolicy, grants = grants,
        contextWindowTokens = contextWindowTokens
    )
    return PlanRunner(
        agentLoop = loop,
        sessionManager = sessionManager,
        provider = provider,
        planner = LlmPlanner(provider, config.model, contextProvider = contextProvider),
        critic = LlmStepCritic(provider, config.model),
        config = config,
        onPlanComplete = onPlanComplete,
        planLog = planLog,
        onEvent = onEvent,
        onProgress = onProgress
    )
}
