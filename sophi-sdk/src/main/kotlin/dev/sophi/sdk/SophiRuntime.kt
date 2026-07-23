package dev.sophi.sdk

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.turnEventBridge
import dev.sophi.learning.LearningPlugin
import dev.sophi.mcp.McpClientManager

class SophiRuntime internal constructor(
    internal val agentLoop: AgentLoop,
    internal val sessionManager: SessionManager,
    internal val pluginRegistry: PluginRegistry,
    internal val config: AgentConfig,
    private val mcpClientManager: McpClientManager? = null,
    /**
     * The learning plugin registered via [RuntimeBuilder.learning], if any. Exposed so embedders
     * that track session lifecycles can call [LearningPlugin.recordSessionEnd] when a session
     * closes; [SophiRuntime] itself has no per-session end signal, so [close] does not call it.
     */
    internal val learningPlugin: LearningPlugin? = null,
    private val toolRegistry: ToolRegistry = ToolRegistry()
) {
    fun close() {
        mcpClientManager?.close()
    }

    fun toolNames(): List<String> = toolRegistry.names()

    suspend fun newSession(title: String? = null): String =
        sessionManager.create(title).also { sessionManager.save(it) }.id.also { id ->
            // Snapshotting is best-effort learning metadata; never let it break session creation.
            runCatching { sessionManager.saveConfigSnapshot(id, config.model, config.systemPrompt) }
        }

    suspend fun turn(sessionId: String, input: String): String {
        val session = sessionManager.load(sessionId)
        pluginRegistry.dispatch(HookPoint.BEFORE_TURN, HookContext(sessionId, userInput = input))
        return try {
            val updated = agentLoop.turn(session, input, config, pluginRegistry.turnEventBridge(sessionId))
            pluginRegistry.dispatch(HookPoint.AFTER_TURN, HookContext(sessionId))
            updated.branch().lastOrNull { it.role == EntryRole.ASSISTANT }?.content ?: ""
        } catch (e: Exception) {
            pluginRegistry.dispatch(HookPoint.ON_ERROR, HookContext(sessionId, error = e))
            throw e
        }
    }
}
