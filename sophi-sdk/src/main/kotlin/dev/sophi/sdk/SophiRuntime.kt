package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
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
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.schedule.engine.ScheduleEngine
import dev.sophi.schedule.notify.Notifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore

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
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val provider: LLMProvider? = null,
    private val contextWindowTokens: Int = 0
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

    suspend fun connectMcpServer(config: McpServerConfig): List<String> {
        val manager = requireNotNull(mcpClientManager) {
            "MCP not configured for this runtime — build it via RuntimeBuilder.mcpConfig(...)"
        }
        return manager.connectOne(config).map { tool ->
            toolRegistry.register(tool)
            tool.name
        }
    }

    suspend fun disconnectMcpServer(serverName: String) {
        val manager = requireNotNull(mcpClientManager) {
            "MCP not configured for this runtime — build it via RuntimeBuilder.mcpConfig(...)"
        }
        manager.disconnect(serverName)
        toolRegistry.names()
            .filter { it.startsWith("${serverName}__") }
            .forEach { toolRegistry.unregister(it) }
    }

    fun scheduleEngine(
        taskStore: TaskStore,
        runLog: RunLog,
        notifier: Notifier,
        maxConcurrentTasks: Int = 4
    ): ScheduleEngine {
        val p = requireNotNull(provider) {
            "provider was not set on this SophiRuntime — build it via RuntimeBuilder"
        }
        require(contextWindowTokens > 0) {
            "contextWindowTokens was not set on this SophiRuntime — build it via RuntimeBuilder"
        }
        return ScheduleEngine(
            taskStore = taskStore,
            runLog = runLog,
            provider = p,
            fullRegistry = toolRegistry,
            sessionManager = sessionManager,
            notifier = notifier,
            model = config.model,
            contextWindowTokens = contextWindowTokens,
            maxConcurrentTasks = maxConcurrentTasks,
            pluginRegistry = pluginRegistry
        )
    }
}
