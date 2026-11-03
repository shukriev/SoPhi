package dev.sophi.cli.goal

import dev.sophi.cli.InputSource
import dev.sophi.cli.LiveRegion
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.session.AgentSession
import dev.sophi.sdk.GoalResult
import dev.sophi.sdk.GoalStatus
import dev.sophi.sdk.SophiRuntime
import dev.sophi.sdk.runGoal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** /goal in the REPL: shows the plan, asks, renders the run and lets ESC stop it. The run itself is [runGoal]. */
class GoalController(
    private val runtime: SophiRuntime,
    private val planLog: PlanLog,
    private val input: InputSource,
    private val liveRegion: LiveRegion,
    private val interactive: Boolean,
    private val tokenViewKey: Char,
    private val autoExitTokenView: Boolean,
    /** Stronger model a low-confidence step escalates to; null = none. */
    private val escalationModel: String? = null,
    /** Mirrored out to a connected companion, exactly as TurnController does for a chat turn. */
    private val onEvent: suspend (TurnEvent) -> Unit = {},
    /** Reviews the goal's recorded outside changes when it ends; null = plan-first off. */
    private val planFirst: dev.sophi.cli.PlanFirstHost? = null,
    private val output: (String) -> Unit
) {
    suspend fun run(session: AgentSession, rawInput: String?): GoalRunResult {
        val args = GoalArgs.parse(rawInput).getOrElse {
            output(it.message ?: GoalArgs.USAGE)
            return GoalRunResult.Declined(session)
        }

        output("Planning…")
        lateinit var renderer: GoalRenderer
        val approved = CompletableDeferred<Unit>()
        suspend fun approve(plan: dev.sophi.core.agent.plan.Plan): Boolean {
            output("Plan ${plan.id} — ${plan.steps.size} steps:")
            plan.steps.forEachIndexed { i, step ->
                val dep = if (step.dependsOn.isEmpty()) "" else "   (after ${step.dependsOn.joinToString(", ")})"
                output("  ${i + 1}. [${step.id}] ${step.instruction}$dep")
            }
            output(args.check?.let { "Stop condition: shell check `$it` (expect exit 0)" } ?: "Stop condition: LLM-judged")
            val yes = !interactive || run {
                output("Run this plan? [y/N] ")
                input.readLine()?.trim()?.lowercase() in setOf("y", "yes")
            }
            if (!yes) { output("Cancelled."); return false }
            renderer = GoalRenderer(session, plan, liveRegion, output, autoExitTokenView)
            approved.complete(Unit)
            return true
        }

        // Two seams, one renderer: onProgress carries the plan-shaped boundaries, onEvent the raw
        // token stream of whichever step is in flight. PlanRunner fires the boundary first, so the
        // renderer has already reset its presenter before the first token of a step arrives.
        // ESC listens only once the plan is approved, so it never competes with the y/N prompt.
        val result: GoalResult? = coroutineScope {
            val runDeferred = async {
                runtime.runGoal(
                    session, args.task, args.check, planLog, escalationModel, ::approve,
                    onProgress = { renderer.handle(it) },
                    onEvent = { onEvent(it); renderer.handleTurnEvent(it) },
                )
            }
            val escDeferred = async {
                approved.await()
                input.awaitControlKeys(tokenViewKey, onToggle = { renderer.toggleTokenView() })
            }
            select {
                runDeferred.onAwait { r -> escDeferred.cancel(); r }
                // runGoal records the interruption before its cancellation finishes; coroutineScope waits for it.
                escDeferred.onAwait { runDeferred.cancel(); null }
            }
        }

        when {
            result == null -> Unit
            result.status == GoalStatus.Declined -> return GoalRunResult.Declined(session)
            result.status == GoalStatus.Failed && !approved.isCompleted -> {
                output("goal: planning failed — ${result.error?.message}")
                return GoalRunResult.Declined(session)
            }
            result.error is dev.sophi.ai.api.BudgetExceededException -> output(dev.sophi.cli.BUDGET_HINT)
        }
        // After the goal's own summary, so the replay note is the session's newest entry.
        result?.changePlan?.let { planFirst?.review(it, session) }
        return GoalRunResult.Ran(session)
    }
}
