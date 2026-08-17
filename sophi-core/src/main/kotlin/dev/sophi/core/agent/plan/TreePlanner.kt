package dev.sophi.core.agent.plan

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Tree-of-thought search widened onto the replan node (experiment).
 *
 * PlanRunner already performs a depth-first search with backtracking: a failed step anchors a
 * replan, the regenerated tail is executed, and the cycle repeats up to maxReplans. That search
 * runs at width 1 — one candidate continuation, executed without ever being compared against
 * an alternative. This decorator widens exactly that node: k candidate tails, scored by a
 * PlanCritic, best one returned. Losing candidates are data and never execute, so the search
 * costs planner and critic calls only — no agent turns, no tool calls, no side effects, and no
 * RunBudget consumption (RunBudget counts executeOnce invocations only).
 *
 * plan() delegates through untouched. The initial plan has no failure evidence to score
 * against, so branching there would rank speculation; replan is the one node in a run holding
 * a concrete reason attached to a concrete step.
 *
 * A single-element [delegates] list short-circuits to a plain delegate call — zero extra LLM
 * calls, byte-identical to pre-search behavior. That is the experiment's off switch.
 *
 * Diversity across candidates is the caller's responsibility: LlmPlanner runs at temperature
 * 0.0 by default, so k delegates sharing one temperature would return k identical tails and
 * the search would be a no-op. Wire distinct temperatures (see ScheduleEngine).
 */
class TreePlanner(
    private val delegates: List<Planner>,
    private val critic: PlanCritic
) : Planner {

    init {
        require(delegates.isNotEmpty()) { "TreePlanner needs at least one delegate planner" }
    }

    override suspend fun plan(goalPrompt: String, context: List<String>): Plan =
        delegates.first().plan(goalPrompt, context)

    override suspend fun replan(
        current: Plan,
        anchorStepId: String,
        reason: String,
        context: List<String>
    ): Plan {
        if (delegates.size == 1) return delegates.first().replan(current, anchorStepId, reason, context)

        val candidates = coroutineScope {
            delegates
                .map { async { it.replan(current, anchorStepId, reason, context) } }
                .awaitAll()
        }
        val scored = coroutineScope {
            candidates
                .map { async { it to critic.score(current.goalPrompt, it, reason) } }
                .awaitAll()
        }
        // maxBy returns the FIRST maximum, so an all-fail-open critic (every score 1.0) picks
        // candidates[0] — deterministically reproducing the old width-1 behavior.
        return scored.maxBy { it.second }.first
    }
}
