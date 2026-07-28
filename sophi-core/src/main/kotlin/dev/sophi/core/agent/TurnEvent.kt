package dev.sophi.core.agent

sealed class TurnEvent {
    data class Token(val text: String) : TurnEvent()
    data class ReasoningToken(val text: String) : TurnEvent()
    data class ToolCallStarted(val name: String, val argsJson: String) : TurnEvent()
    data class ToolCallFinished(
        val name: String,
        val result: String,
        val isError: Boolean = false,
        val durationMillis: Long = 0
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
}
