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
    val error: Throwable? = null
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
