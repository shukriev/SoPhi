package dev.sophi.ai.api

import kotlinx.coroutines.flow.Flow

interface LLMProvider {
    val name: String
    suspend fun complete(request: CompletionRequest): LLMResponse
    /**
     * Streams structured events: final-answer content, reasoning/chain-of-thought (if the
     * provider/backend exposes it), and fully-merged tool-call decisions. Errors propagate as
     * [IllegalStateException] wrapping the underlying provider error — callers should use
     * Flow.catch to handle them.
     */
    fun stream(request: CompletionRequest): Flow<StreamEvent>
}
