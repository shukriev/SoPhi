package dev.sophi.sdk

import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.plan.LlmPlanner
import dev.sophi.core.agent.plan.LlmStepCritic
import dev.sophi.core.agent.plan.Plan
import dev.sophi.core.agent.plan.PlanFinalStatus
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.agent.plan.PlanProgressEvent
import dev.sophi.core.agent.plan.PlanRunner
import dev.sophi.core.agent.plan.PlanRunnerConfig
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.dryrun.ChangePlan
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.undo.undoableRound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class GoalStatus { Met, Exhausted, Failed, Interrupted, Declined }

/** How a [runGoal] ended. [changePlan] is what the run recorded under plan-first, for the host to review. */
data class GoalResult(val status: GoalStatus, val reply: String, val error: Throwable? = null, val changePlan: ChangePlan? = null)

/**
 * Plans [task], asks [approve], then runs the plan step by step on this runtime's loop until it is done:
 * [check] is a shell command that must exit 0, or null for the model to judge. Appends the task and one
 * summary entry to [session] and saves it; one /undo reverses the whole run. Cancelling records the
 * interruption, then rethrows.
 */
suspend fun SophiRuntime.runGoal(
    session: AgentSession,
    task: String,
    check: String? = null,
    planLog: PlanLog? = null,
    escalationModel: String? = null,
    approve: suspend (Plan) -> Boolean = { true },
    onProgress: suspend (PlanProgressEvent) -> Unit = {},
    onEvent: suspend (TurnEvent) -> Unit = {},
): GoalResult {
    val llm = checkNotNull(provider) { "No provider" }
    val cfg = config
    val planner = LlmPlanner(llm, cfg.model, contextProvider = { contextFor(session.id, it) })
    val plan = try {
        planner.plan(task)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return GoalResult(GoalStatus.Failed, "", e)
    }
    if (!approve(plan)) return GoalResult(GoalStatus.Declined, "")

    session.append(EntryRole.USER, task, mapOf("goal" to "true", "planId" to plan.id))
    var lastOutput = ""
    var planVersion = plan.version
    val runner = PlanRunner(
        agentLoop, sessionManager, llm, planner, LlmStepCritic(llm, cfg.model),
        PlanRunnerConfig(cfg.model, cfg.maxTokens, cfg.systemPrompt, escalationModel, allowParallelSteps = false),
        planLog = planLog,
        onEvent = onEvent,
        onProgress = { event ->
            when (event) {
                is PlanProgressEvent.StepFinished -> lastOutput = event.output
                is PlanProgressEvent.Replanned -> planVersion = event.plan.version
                else -> Unit
            }
            onProgress(event)
        },
    )
    val stop = check?.let { StopCondition.ShellCheck(it) } ?: StopCondition.LlmJudged
    val (result, recorded) = try {
        undoableRound(session, checkpointStore, "goal") {
            planFirstScope(session.id, task) { runner.run(session.id, task, stop, initialPlan = plan) }
        }
    } catch (e: CancellationException) {
        withContext(NonCancellable) {
            session.append(EntryRole.ASSISTANT, "[goal interrupted]\n\n$lastOutput", emptyMap())
            runCatching { sessionManager.save(session) }
            learningPlugin?.recordPlanOutcome(session.id, "goal \"${task.take(60)}\" → Interrupted")
            settleExternalTurn(session.id, task, lastOutput)
        }
        throw e
    } catch (e: Exception) {
        session.append(EntryRole.ASSISTANT, "[goal failed: ${e.message ?: "unknown error"}]", emptyMap())
        runCatching { sessionManager.save(session) }
        settleExternalTurn(session.id, task, "", e)
        return GoalResult(GoalStatus.Failed, "", e)
    }

    val status = if (result.finalStatus == PlanFinalStatus.Met) GoalStatus.Met else GoalStatus.Exhausted
    val plural = if (result.replans.size == 1) "" else "s"
    val header = "[goal: ${status.name} — ${result.totalSteps} steps, ${result.replans.size} replan$plural]"
    session.append(EntryRole.ASSISTANT, "$header\n\n${result.finalOutput}", emptyMap())
    runCatching { sessionManager.save(session) }
    val note = buildString {
        append("goal \"${task.take(60)}\" → ${status.name} (${result.totalSteps} steps, plan v$planVersion)")
        result.replans.take(5).forEach { append("; replan at ${it.stepId}: ${it.reason}") }
    }
    learningPlugin?.recordPlanOutcome(session.id, note)
    settleExternalTurn(session.id, task, result.finalOutput)
    return GoalResult(status, result.finalOutput, changePlan = recorded)
}
