package dev.sophi.ai.api

import kotlinx.coroutines.flow.Flow

interface LLMProvider {
    val name: String
    suspend fun complete(request: CompletionRequest): LLMResponse
    /**
     * Streams response text chunks. Errors propagate as [IllegalStateException] wrapping
     * the underlying provider error — callers should use Flow.catch to handle them.
     */
    fun stream(request: CompletionRequest): Flow<String>
}
