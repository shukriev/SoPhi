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
}
