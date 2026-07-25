package dev.sophi.sdk

import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal fun LLMResponse.toStreamFlow(): Flow<StreamEvent> = when (this) {
    is LLMResponse.Text -> flow { emit(StreamEvent.Content(content)) }
    is LLMResponse.ToolUse -> flow { emit(StreamEvent.ToolCallsReady(calls)) }
    is LLMResponse.Error -> flow { throw IllegalStateException(message, cause) }
}
