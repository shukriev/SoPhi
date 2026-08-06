package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.plan.PlanFinalStatus
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.agent.plan.PlanOutcome
import dev.sophi.core.agent.plan.PlanRunnerConfig
import dev.sophi.core.agent.plan.StopCondition
import dev.sophi.core.agent.plan.buildPlanRunner
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The user-invoked half of the planner (the model-invoked half is decompose_goal). Shares its
 * whole construction path with that tool via buildPlanRunner. Runs strictly sequentially:
 * a human may be sitting at a confirmation prompt, and ADR-018 decision #5 exists so
 * overlapping prompts cannot happen.
 */
class PlanCommand(
    private val provider: LLMProvider,
    private val registry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val config: AgentConfig,
    /** Total context window of `config.model`, in tokens — see AgentLoop. */
    private val contextWindowTokens: Int,
    private val confirmationPolicy: ConfirmationPolicy,
    private val planLog: PlanLog?,
    private val onEvent: suspend (TurnEvent) -> Unit = {},
    private val liveRegion: LiveRegion = LiveRegion(StringBuilder()) { 80 },
    private val echo: (String) -> Unit
) {
    suspend fun run(goal: String, session: AgentSession): AgentSession = coroutineScope {
        val renderer = PlanProgressRenderer(liveRegion, echo)
        val animationJob = launch { renderer.animate() }
        try {
            val runner = buildPlanRunner(
                provider = provider,
                registry = registry,
                sessionManager = sessionManager,
                config = PlanRunnerConfig(
                    model = config.model,
                    maxTokens = config.maxTokens,
                    allowParallelSteps = false
                ),
                contextWindowTokens = contextWindowTokens,
                confirmationPolicy = confirmationPolicy,
                planLog = planLog,
                onEvent = { event -> onEvent(event); renderer.onTurnEvent(event) },
                onProgress = renderer::onProgress
            )
            val outcome = runner.run(session.id, goal, StopCondition.LlmJudged)
            val summary = render(goal, outcome)
            echo(summary)
            session.append(EntryRole.ASSISTANT, summary)
            sessionManager.save(session)
            session
        } finally {
            animationJob.cancel()
            liveRegion.clear()
        }
    }

    private fun render(goal: String, outcome: PlanOutcome): String = buildString {
        appendLine("Goal: $goal")
        appendLine("Plan ${outcome.planId} — ${outcome.finalStatus} " +
            "(${outcome.totalSteps} steps, ${outcome.replans.size} replans, " +
            "${outcome.decompositions.size} sub-plans)")
        outcome.finalSteps.forEach {
            appendLine("[${it.id}] ${it.status}${it.confidence?.let { c -> " ($c)" } ?: ""} — ${it.instruction}")
        }
        outcome.decompositions.forEach {
            appendLine("[${it.stepId}] expanded into ${it.childPlanId} (${it.childStepCount} steps, ${it.childStatus})")
        }
        if (outcome.finalStatus != PlanFinalStatus.Met) {
            appendLine("Goal was not fully met — inspect the plan log for ${outcome.planId}.")
        }
        append(outcome.finalOutput)
    }
}
