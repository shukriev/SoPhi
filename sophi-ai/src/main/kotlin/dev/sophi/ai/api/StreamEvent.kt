package dev.sophi.ai.api

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Reasoning(val text: String) : StreamEvent()
    data class ToolCallsReady(val calls: List<ToolCall>) : StreamEvent()
}
