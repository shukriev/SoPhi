package dev.sophi.core.agent.plan

import dev.sophi.core.agent.TurnEvent

/**
 * Progress signal for a PlanRunner.run() call. PlanRunner has no other progress surface — an
 * interactive caller cannot otherwise tell that a step started. With allowParallelSteps = true,
 * events from different steps interleave; consumers must key on stepId. With the default
 * (false) the stream is strictly linear.
 */
sealed class PlanEvent {
    /** A plan PlanRunner generated itself. Not emitted for a caller-supplied initialPlan. */
    data class PlanReady(val plan: Plan) : PlanEvent()
    /** One agentLoop.turn() attempt. attempt is 1, or 2 for an escalation re-run. */
    data class StepAttempt(
        val step: PlanStep, val planVersion: Int, val model: String,
        val childSessionId: String, val attempt: Int
    ) : PlanEvent()
    data class StepTurn(val stepId: String, val event: TurnEvent) : PlanEvent()
    data class Escalating(val stepId: String, val confidence: Double, val toModel: String) : PlanEvent()
    /** step carries the final status, confidence and modelOverride. */
    data class StepFinished(val step: PlanStep, val planVersion: Int) : PlanEvent()
    data class Replanned(val event: ReplanEvent, val plan: Plan) : PlanEvent()
}
