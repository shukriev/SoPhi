package dev.sophi.sdk

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry

class SophiRuntime internal constructor(
    internal val agentLoop: AgentLoop,
    internal val sessionManager: SessionManager,
    internal val pluginRegistry: PluginRegistry,
    internal val config: AgentConfig
) {
    suspend fun newSession(title: String? = null): String =
        sessionManager.create(title).also { sessionManager.save(it) }.id

    suspend fun turn(sessionId: String, input: String): String {
        val session = sessionManager.load(sessionId)
        pluginRegistry.dispatch(HookPoint.BEFORE_TURN, HookContext(sessionId, userInput = input))
        return try {
            val updated = agentLoop.turn(session, input, config)
            pluginRegistry.dispatch(HookPoint.AFTER_TURN, HookContext(sessionId))
            updated.branch().lastOrNull { it.role == EntryRole.ASSISTANT }?.content ?: ""
        } catch (e: Exception) {
            pluginRegistry.dispatch(HookPoint.ON_ERROR, HookContext(sessionId, error = e))
            throw e
        }
    }
}
