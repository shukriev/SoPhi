package dev.sophi.ai.api

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Reasoning(val text: String) : StreamEvent()
    data class ToolCallsReady(val calls: List<ToolCall>) : StreamEvent()

    /**
     * Why the model stopped this round, verbatim from the provider ("stop", "length", ...).
     * Only reported by providers that expose one; absent means "not reported", not "normal".
     */
    data class Finish(val reason: String) : StreamEvent()

    /**
     * Fires once per round, after that round's content/tool-calls are complete. Both providers
     * report *cumulative* input tokens for the whole prompt they were just sent, so a consumer
     * reads the latest Usage event as the current total — no manual summing.
     */
    data class Usage(val usage: TokenUsage) : StreamEvent()
}
