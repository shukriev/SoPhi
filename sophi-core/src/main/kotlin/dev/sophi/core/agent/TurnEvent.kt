package dev.sophi.core.agent

sealed class TurnEvent {
    data class Token(val text: String) : TurnEvent()
    data class ReasoningToken(val text: String) : TurnEvent()
    /** [callId] is the model's id for the call, the one a confirmation request carries; empty when unknown. */
    data class ToolCallStarted(val name: String, val argsJson: String, val callId: String = "") : TurnEvent()
    data class ToolCallFinished(
        val name: String,
        val result: String,
        val isError: Boolean = false,
        val durationMillis: Long = 0,
        val argsJson: String = "",
        val callId: String = "",
    ) : TurnEvent()
    /**
     * Fired around a confirmationPolicy.confirm() call that's actually needed (at least one
     * non-SAFE, ungranted call in the round). A UI rendering something else concurrently while
     * a turn is in flight (a live-updating spinner, say) needs this to know a blocking,
     * human-facing prompt is about to own the terminal — nothing else should redraw over it
     * until ConfirmationFinished arrives.
     */
    data class ConfirmationStarted(val toolNames: List<String>) : TurnEvent()
    object ConfirmationFinished : TurnEvent()
    /** The user's mid-turn messages were handed to the model, before its next request. */
    data class SteerApplied(val texts: List<String>) : TurnEvent()
    /** A message queued as a follow-up is starting as its own turn. */
    data class FollowUpStarted(val text: String) : TurnEvent()
    /** The turn recorded changes instead of making them; [plan] is saved, pending the user's approval. */
    data class PlanReady(val plan: dev.sophi.core.dryrun.ChangePlan) : TurnEvent()
    /** A plan-first turn changed nothing but ran lookups only their server vouched were read-only: [reads] lists them. */
    data class HintedReads(val reads: List<dev.sophi.core.dryrun.PlanRead>) : TurnEvent()
    /** One model request finished: its [usage], and [generationMillis] from its first streamed event to its end (0 when none came). */
    data class RoundUsage(val usage: dev.sophi.ai.api.TokenUsage, val generationMillis: Long) : TurnEvent()
    /** [from] was unavailable ([reason]), so the rest of this turn runs on [to], the next model of the fallback chain. */
    data class ModelFallback(val from: String, val to: String, val reason: String) : TurnEvent()
}
