package dev.sophi.sdk

import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// Mirrors both real providers: usage arrives last, once the round's content/tool-calls are done.
internal fun LLMResponse.toStreamFlow(): Flow<StreamEvent> = when (this) {
    is LLMResponse.Text -> flow {
        emit(StreamEvent.Content(content))
        emit(StreamEvent.Usage(usage))
    }
    is LLMResponse.ToolUse -> flow {
        emit(StreamEvent.ToolCallsReady(calls))
        emit(StreamEvent.Usage(usage))
    }
    is LLMResponse.Error -> flow { throw IllegalStateException(message, cause) }
}
