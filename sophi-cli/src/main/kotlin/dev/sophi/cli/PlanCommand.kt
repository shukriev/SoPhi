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
import dev.sophi.core.undo.CheckpointStore
import dev.sophi.core.undo.undoableRound
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Default for callers (mainly tests) with no real terminal — never answers a confirmation. */
internal object NoOpInputSource : InputSource {
    override suspend fun readLine(prefill: String): String? = null
    override suspend fun awaitEsc() {}
    override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit, onKey: (suspend (TurnKey) -> Unit)?) = awaitCancellation()
    override suspend fun awaitYesNo(): Boolean = false
}

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
    private val input: InputSource = NoOpInputSource,
    /** Snapshots before the run, so /undo reverses the whole plan in one step. Null = no checkpoints. */
    private val checkpoints: CheckpointStore? = null,
    private val audit: dev.sophi.core.audit.AuditSink? = null,
    private val permissions: dev.sophi.core.permissions.PermissionRules? = null,
    private val echo: (String) -> Unit
) {
    suspend fun run(goal: String, session: AgentSession): AgentSession = coroutineScope {
        val renderer = PlanProgressRenderer(liveRegion, echo)
        val animationJob = launch { renderer.animate() }
        // A confirmation prompt during a plan step answers through awaitYesNo(), which never
        // reads the terminal itself — only a concurrently-running awaitControlKeys() loop can
        // ever satisfy it (see InputSource.awaitYesNo's doc). TurnController races this same
        // loop against a normal chat turn; a /plan run needs the identical loop running for its
        // whole duration, or every confirmation hangs forever no matter what the user types.
        val controlKeysJob = launch { input.awaitControlKeys('\u0000', onToggle = {}) }
        try {
            val runner = buildPlanRunner(
                provider = provider,
                registry = registry,
                sessionManager = sessionManager,
                config = PlanRunnerConfig(
                    model = config.model,
                    maxTokens = config.maxTokens,
                    systemPrompt = config.systemPrompt,
                    allowParallelSteps = false
                ),
                contextWindowTokens = contextWindowTokens,
                confirmationPolicy = confirmationPolicy,
                planLog = planLog,
                onEvent = { event -> onEvent(event); renderer.onTurnEvent(event) },
                onProgress = renderer::onProgress,
                audit = audit,
                permissions = permissions
            )
            // saved even when the run throws: /undo reloads the session, and the round entry must be there
            val outcome = try {
                undoableRound(session, checkpoints, "plan") { runner.run(session.id, goal, StopCondition.LlmJudged) }
            } catch (e: dev.sophi.ai.api.BudgetExceededException) {
                // The planner or the judge was refused: end /plan with what was saved, not the whole session.
                echo("${e.message}\n$BUDGET_HINT")
                return@coroutineScope session
            } finally {
                runCatching { sessionManager.save(session) }
            }
            val summary = render(goal, outcome)
            echo(summary)
            session.append(EntryRole.ASSISTANT, summary)
            sessionManager.save(session)
            session
        } finally {
            animationJob.cancel()
            controlKeysJob.cancel()
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
