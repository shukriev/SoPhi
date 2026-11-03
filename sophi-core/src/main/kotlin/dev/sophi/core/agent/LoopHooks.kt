package dev.sophi.core.agent

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.core.tools.ConfirmationRequest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** What a hook asks for. Each hook point reads only the results it names; any other result is ignored. */
sealed interface HookResult {
    data object Continue : HookResult
    /** BEFORE_TOOL: don't run the call, the model gets [reason] as an error. ON_CONFIRMATION: deny it. */
    data class Veto(val reason: String) : HookResult
    /** BEFORE_TOOL: run the call with these arguments instead. They still go through every risk, rule, plan-first and confirmation check. */
    data class Rewrite(val argumentsJson: String) : HookResult
    /** BEFORE_PROVIDER_REQUEST: send this request instead. */
    data class Request(val request: CompletionRequest) : HookResult
}

/**
 * The plugin hooks [AgentLoop] calls itself, so every host gets them. sophi-extensions' PluginRegistry
 * implements it; it lives here because the loop can't see that module.
 */
interface LoopHooks {
    /** [HookResult.Veto], [HookResult.Rewrite] or [HookResult.Continue]. */
    suspend fun beforeTool(sessionId: String, name: String, argumentsJson: String): HookResult = HookResult.Continue
    suspend fun afterTool(sessionId: String, name: String, argumentsJson: String, result: String, failed: Boolean, durationMillis: Long) {}
    suspend fun beforeRequest(sessionId: String, request: CompletionRequest): CompletionRequest = request
    /** Around both mid-turn and between-turn compaction. */
    suspend fun compaction(sessionId: String, after: Boolean) {}
    /** A reason when a hook denies [request]; null = the confirmation policy decides. A hook can deny, never allow. */
    suspend fun denies(sessionId: String, request: ConfirmationRequest): String? = null
}

/** Carries a loop's hooks into nested loops (subagents, plan steps), like [dev.sophi.core.audit.AuditScope]. */
class HookScope(val hooks: LoopHooks) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HookScope>
}
