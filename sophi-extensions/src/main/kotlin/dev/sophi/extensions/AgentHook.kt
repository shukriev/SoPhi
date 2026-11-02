package dev.sophi.extensions

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.core.agent.HookResult

/** New values may be added; a plugin's `when` over this should keep an `else` branch. */
enum class HookPoint {
    BEFORE_TURN,
    AFTER_TURN,
    /** Fired by AgentLoop before any check; [AgentHook.decide] may return [HookResult.Veto] or [HookResult.Rewrite]. */
    BEFORE_TOOL,
    AFTER_TOOL,
    ON_ERROR,
    ON_SESSION_START,
    ON_SESSION_END,
    /** [HookContext.request] is the request; [AgentHook.decide] may return [HookResult.Request] to send another. */
    BEFORE_PROVIDER_REQUEST,
    BEFORE_COMPACTION,
    AFTER_COMPACTION,
    /** A call is about to be confirmed; [AgentHook.decide] may return [HookResult.Veto] to deny it. A hook can't approve. */
    ON_CONFIRMATION
}

data class HookContext(
    val sessionId: String,
    val userInput: String? = null,
    val toolName: String? = null,
    val error: Throwable? = null,
    val argumentsJson: String? = null,
    val toolResult: String? = null,
    val success: Boolean? = null,
    val durationMillis: Long? = null,
    val assistantReply: String? = null,
    val ambient: Boolean = false,
    /** Ambient text heard during a detected meeting: memory must not track commitments from it. */
    val inMeeting: Boolean = false,
    /** BEFORE_PROVIDER_REQUEST: the request about to be sent. */
    val request: CompletionRequest? = null,
)

interface AgentHook {
    val point: HookPoint
    suspend fun invoke(context: HookContext) {}

    /** Like [invoke], but may steer: see each [HookPoint] for the results it reads. Defaults to [invoke], then carry on. */
    suspend fun decide(context: HookContext): HookResult {
        invoke(context)
        return HookResult.Continue
    }
}

interface SophiPlugin {
    val name: String
    val version: String get() = "1.0.0"
    fun hooks(): List<AgentHook>
}

/** Where a contribution goes: STABLE -> system prompt (cacheable prefix); VOLATILE -> this turn's request only. */
enum class Placement { STABLE, VOLATILE }

/**
 * Optional capability: a [SophiPlugin] additionally implementing this may return a block
 * of text to inject into the current turn's context. Failures and timeouts are swallowed
 * by [PluginRegistry.collectContext] — contribution is always best-effort. Anything that
 * changes per message (recall, hints) must be [Placement.VOLATILE], or it rewrites the system
 * prompt every turn and defeats prompt caching (ADR-041 section M2).
 */
interface ContextContributor {
    val placement: Placement get() = Placement.STABLE
    suspend fun contribute(sessionId: String, userInput: String): String?
}
