package dev.sophi.ai.api

import kotlinx.coroutines.flow.Flow

interface LLMProvider {
    val name: String
    suspend fun complete(request: CompletionRequest): LLMResponse
    fun stream(request: CompletionRequest): Flow<String>
}
