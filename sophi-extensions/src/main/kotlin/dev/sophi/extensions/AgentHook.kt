package dev.sophi.extensions

enum class HookPoint {
    BEFORE_TURN,
    AFTER_TURN,
    BEFORE_TOOL,
    AFTER_TOOL,
    ON_ERROR
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
    val assistantReply: String? = null
)

interface AgentHook {
    val point: HookPoint
    suspend fun invoke(context: HookContext)
}

interface SophiPlugin {
    val name: String
    val version: String get() = "1.0.0"
    fun hooks(): List<AgentHook>
}

/**
 * Optional capability: a [SophiPlugin] additionally implementing this may return a block
 * of text to inject into the current turn's context. Failures and timeouts are swallowed
 * by [PluginRegistry.collectContext] — contribution is always best-effort.
 */
interface ContextContributor {
    suspend fun contribute(sessionId: String, userInput: String): String?
}
