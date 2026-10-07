package dev.sophi.sdk

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Answers complete() with [replies] in order; a null reply throws, like a dead server. */
class ScriptedProvider(vararg replies: String?) : LLMProvider {
    private val queue = ArrayDeque(replies.toList())
    val requests = mutableListOf<CompletionRequest>()
    override val name = "scripted"
    override suspend fun complete(request: CompletionRequest): LLMResponse {
        requests += request
        val reply = queue.removeFirstOrNull() ?: throw IllegalStateException("no reply scripted")
        return LLMResponse.Text(content = reply, usage = TokenUsage(0, 0))
    }
    override fun stream(request: CompletionRequest): Flow<StreamEvent> = emptyFlow()
}
