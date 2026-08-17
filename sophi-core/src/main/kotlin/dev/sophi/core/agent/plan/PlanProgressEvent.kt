package dev.sophi.core.agent.plan

/**
 * Live-progress counterpart to the batch results in PlanOutcome — fired as a run happens rather
 * than returned once it finishes, so a caller (the CLI's /plan and /goal) can show something
 * between the long silences of chained agentLoop.turn calls instead of only a summary at the very
 * end.
 *
 * This is PlanRunner's *narrative* stream: plan-shaped milestones, one per boundary. The raw
 * per-token TurnEvent stream stays on PlanRunner's separate `onEvent` seam — subsystems that only
 * mirror turn events (the hub/companion bridge) want it unwrapped, and renderers that want both
 * subscribe to both. `onProgress` fires the boundary first, so a renderer can reset its per-step
 * state before the first TurnEvent of that step arrives.
 *
 * With allowParallelSteps = true, events from different steps interleave; consumers must key on
 * stepId. With the default (false) the stream is strictly linear.
 */
sealed class PlanProgressEvent {
    /** A plan PlanRunner generated itself. Not emitted for a caller-supplied initialPlan. */
    data class PlanReady(val plan: Plan) : PlanProgressEvent()

    /** Once per step, including a step that turns out to be decomposed rather than executed. */
    data class StepStarted(val planId: String, val step: PlanStep, val planVersion: Int) : PlanProgressEvent()

    /**
     * One agentLoop.turn() attempt within a step — so a renderer can name the model actually used
     * and the child session it ran in. attempt is 1, or 2 for an escalation re-run. A decomposed
     * step has no attempts of its own; its sub-plan's steps emit their own.
     */
    data class StepAttempt(
        val planId: String, val step: PlanStep, val planVersion: Int,
        val model: String, val childSessionId: String, val attempt: Int
    ) : PlanProgressEvent()

    /** Confidence came back under the threshold and an escalationModel is configured. */
    data class Escalating(val stepId: String, val confidence: Double, val toModel: String) : PlanProgressEvent()

    /** step carries the final status, confidence and modelOverride. */
    data class StepFinished(val planId: String, val step: PlanStep, val planVersion: Int) : PlanProgressEvent()

    /** [plan] is the replacement plan, already version-bumped. */
    data class Replanned(val plan: Plan, val stepId: String, val reason: String) : PlanProgressEvent()

    data class Decomposed(
        val stepId: String, val childPlanId: String, val trigger: DecompositionTrigger
    ) : PlanProgressEvent()
}
