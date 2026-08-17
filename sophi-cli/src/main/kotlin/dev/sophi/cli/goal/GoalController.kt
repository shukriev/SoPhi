package dev.sophi.cli.goal

import dev.sophi.ai.api.LLMProvider
import dev.sophi.cli.InputSource
import dev.sophi.cli.LiveRegion
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.plan.PlanFinalStatus
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.agent.plan.PlanOutcome
import dev.sophi.core.agent.plan.PlanRunner
import dev.sophi.core.agent.plan.PlanRunnerConfig
import dev.sophi.core.agent.plan.Planner
import dev.sophi.core.agent.plan.StepCritic
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.learning.LearningPlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

class GoalController(
    private val agentLoop: AgentLoop,
    private val sessionManager: SessionManager,
    private val provider: LLMProvider,
    private val planner: Planner,
    private val critic: StepCritic,
    private val runnerConfig: PlanRunnerConfig,
    private val planLog: PlanLog,
    private val input: InputSource,
    private val liveRegion: LiveRegion,
    private val interactive: Boolean,
    private val tokenViewKey: Char,
    private val autoExitTokenView: Boolean,
    private val learning: LearningPlugin?,
    /** Mirrored out to a connected companion, exactly as TurnController does for a chat turn. */
    private val onEvent: suspend (TurnEvent) -> Unit = {},
    private val onTurnSettled: suspend (userInput: String, assistantReply: String, error: Throwable?) -> Unit,
    private val output: (String) -> Unit
) {
    suspend fun run(session: AgentSession, rawInput: String?, trigger: GoalTrigger = GoalTrigger.Explicit): GoalRunResult {
        val argsResult = when (trigger) {
            is GoalTrigger.Explicit -> GoalArgs.parse(rawInput)
            is GoalTrigger.Autonomous -> Result.success(GoalArgs(task = rawInput.orEmpty(), check = null))
        }
        val args = argsResult.getOrElse {
            output(it.message ?: GoalArgs.USAGE)
            return GoalRunResult.Declined(session, DeclineReason.Failed)
        }

        output("Planning…")
        val plan = try {
            planner.plan(args.task)
        } catch (e: Exception) {
            output("goal: planning failed — ${e.message}")
            return GoalRunResult.Declined(session, DeclineReason.Failed)
        }

        val stopCondition = args.check?.let { StopCondition.ShellCheck(it) } ?: StopCondition.LlmJudged

        output("Plan ${plan.id} — ${plan.steps.size} steps:")
        plan.steps.forEachIndexed { i, step ->
            val dep = if (step.dependsOn.isEmpty()) "" else "   (after ${step.dependsOn.joinToString(", ")})"
            output("  ${i + 1}. [${step.id}] ${step.instruction}$dep")
        }
        output(
            when (stopCondition) {
                is StopCondition.LlmJudged -> "Stop condition: LLM-judged"
                is StopCondition.ShellCheck -> "Stop condition: shell check `${stopCondition.command}` (expect exit 0)"
            }
        )

        val approved = if (!interactive) {
            true
        } else {
            val prompt = when (trigger) {
                is GoalTrigger.Explicit -> "Run this plan? [y/N] "
                is GoalTrigger.Autonomous -> "Run this plan? [y/N]  (n = just answer normally) "
            }
            output(prompt)
            val answer = input.readLine()?.trim()?.lowercase()
            answer == "y" || answer == "yes"
        }
        if (!approved) {
            output("Cancelled.")
            return GoalRunResult.Declined(session, DeclineReason.UserDeclined)
        }

        session.append(EntryRole.USER, args.task, mapOf("goal" to "true", "planId" to plan.id))

        val renderer = GoalRenderer(session, plan, liveRegion, output, tokenViewKey, autoExitTokenView)
        // Two seams, one renderer: onProgress carries the plan-shaped boundaries, onEvent the raw
        // token stream of whichever step is in flight. PlanRunner fires the boundary first, so the
        // renderer has already reset its presenter before the first token of a step arrives.
        val runner = PlanRunner(
            agentLoop, sessionManager, provider, planner, critic, runnerConfig,
            planLog = planLog,
            onEvent = { onEvent(it); renderer.handleTurnEvent(it) },
            onProgress = { renderer.handle(it) }
        )

        val outcome: Result<PlanOutcome>? = coroutineScope {
            val runDeferred = async { runCatching { runner.run(session.id, args.task, stopCondition, initialPlan = plan) } }
            val escDeferred = async { input.awaitControlKeys(tokenViewKey) { renderer.toggleTokenView() } }
            select {
                runDeferred.onAwait { result -> escDeferred.cancel(); result }
                escDeferred.onAwait { runDeferred.cancel(); null }
            }
        }

        return when {
            outcome == null -> {
                session.append(
                    EntryRole.ASSISTANT,
                    "[goal interrupted]\n\n${renderer.lastStepOutput}",
                    emptyMap()
                )
                runCatching { sessionManager.save(session) }
                learning?.recordPlanOutcome(session.id, "goal \"${args.task.take(60)}\" → Interrupted")
                onTurnSettled(args.task, renderer.lastStepOutput, null)
                GoalRunResult.Ran(session)
            }
            outcome.isFailure -> {
                val msg = outcome.exceptionOrNull()?.message ?: "unknown error"
                session.append(EntryRole.ASSISTANT, "[goal failed: $msg]", emptyMap())
                runCatching { sessionManager.save(session) }
                onTurnSettled(args.task, "", outcome.exceptionOrNull())
                GoalRunResult.Ran(session)
            }
            else -> {
                val result = outcome.getOrThrow()
                val statusWord = if (result.finalStatus == PlanFinalStatus.Met) "Met" else "Exhausted"
                val plural = if (result.replans.size == 1) "" else "s"
                val header = "[goal: $statusWord — ${result.totalSteps} steps, ${result.replans.size} replan$plural]"
                session.append(EntryRole.ASSISTANT, "$header\n\n${result.finalOutput}", emptyMap())
                runCatching { sessionManager.save(session) }
                val note = buildString {
                    append("goal \"${args.task.take(60)}\" → $statusWord (${result.totalSteps} steps, plan v${renderer.currentPlan.version})")
                    result.replans.take(5).forEach { append("; replan at ${it.stepId}: ${it.reason}") }
                }
                learning?.recordPlanOutcome(session.id, note)
                onTurnSettled(args.task, result.finalOutput, null)
                GoalRunResult.Ran(session)
            }
        }
    }
}
