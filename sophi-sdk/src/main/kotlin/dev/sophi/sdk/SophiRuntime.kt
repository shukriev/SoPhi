package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.turnEventBridge
import dev.sophi.learning.LearningPlugin
import dev.sophi.memory.MemoryPlugin
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.schedule.engine.ScheduleEngine
import dev.sophi.schedule.notify.Notifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import dev.sophi.skills.InstallResult
import dev.sophi.skills.Skill
import dev.sophi.skills.SkillInstaller
import dev.sophi.skills.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path

private fun AgentSession.lastAssistantReply(): String =
    branch().lastOrNull { it.role == EntryRole.ASSISTANT }?.content ?: ""

class SophiRuntime internal constructor(
    internal val agentLoop: AgentLoop,
    val sessionManager: SessionManager,
    internal val pluginRegistry: PluginRegistry,
    val config: AgentConfig,
    private val mcpClientManager: McpClientManager? = null,
    /**
     * The learning plugin registered via [RuntimeBuilder.learning], if any. Exposed so embedders
     * that track session lifecycles can call [LearningPlugin.recordSessionEnd] when a session
     * closes; [SophiRuntime] itself has no per-session end signal, so [close] does not call it.
     */
    val learningPlugin: LearningPlugin? = null,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val provider: LLMProvider? = null,
    private val contextWindowTokens: Int = 0,
    private val skillsDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "skills"),
    val memoryPlugin: MemoryPlugin? = null
) {
    private val skillInstaller = SkillInstaller()

    fun close() {
        mcpClientManager?.close()
        memoryPlugin?.close()
    }

    fun toolNames(): List<String> = toolRegistry.names()

    fun skills(): List<Pair<String, Skill>> = SkillRegistry.load(skillsDir, skillsDir).all()
    fun installSkill(source: String): InstallResult = skillInstaller.install(source, skillsDir)
    fun removeSkill(id: String): Boolean = skillInstaller.remove(skillsDir, id)

    suspend fun newSession(title: String? = null): String =
        sessionManager.create(title).also { sessionManager.save(it) }.id.also { id ->
            // Snapshotting is best-effort learning metadata; never let it break session creation.
            runCatching { sessionManager.saveConfigSnapshot(id, config.model, config.systemPrompt) }
        }

    suspend fun turn(sessionId: String, input: String): String = streamTurn(sessionId, input) { }

    suspend fun streamTurn(sessionId: String, input: String, onEvent: suspend (TurnEvent) -> Unit): String =
        streamTurn(sessionManager.load(sessionId), input, onEvent).lastAssistantReply()

    /**
     * Runs a turn against an already-loaded [session] and returns the updated one. Prefer this
     * over the id-based overload when you are already threading the session yourself — it does
     * not reload from disk.
     */
    suspend fun streamTurn(
        session: AgentSession,
        input: String,
        onEvent: suspend (TurnEvent) -> Unit
    ): AgentSession {
        pluginRegistry.dispatch(HookPoint.BEFORE_TURN, HookContext(session.id, userInput = input))
        // BEFORE_TURN fires first so a plugin can prime state that its own contribute() then reads.
        // The merged prompt is scoped to this call only — config stays the base prompt.
        val extra = pluginRegistry.collectContext(session.id, input)
            .takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        val turnConfig = if (extra == null) config
            else config.copy(systemPrompt = listOfNotNull(config.systemPrompt, extra).joinToString("\n\n"))
        val bridge = pluginRegistry.turnEventBridge(session.id)
        // Buffered here, not only in the caller's UI layer: an interrupted turn still has to
        // report what the assistant managed to say, or AFTER_TURN fires with a null
        // assistantReply and the hooks that encode the exchange drop the turn.
        val partial = StringBuilder()
        return try {
            val updated = agentLoop.streamTurn(session, input, turnConfig) { event ->
                if (event is TurnEvent.Token) partial.append(event.text)
                bridge(event)
                onEvent(event)
            }
            settle(session.id, input, updated.lastAssistantReply())
            updated
        } catch (e: CancellationException) {
            // An interrupt is not a failure. Dispatching ON_ERROR here would make LearningPlugin
            // mark the session errored, so recordSessionEnd would write outcome=error for a turn
            // the user simply stopped.
            settle(session.id, input, partial.toString())
            throw e
        } catch (e: Exception) {
            pluginRegistry.dispatch(HookPoint.ON_ERROR, HookContext(session.id, error = e))
            throw e
        }
    }

    /**
     * Dispatches AFTER_TURN under [NonCancellable] so an interrupted turn is still recorded —
     * the caller cancelling us must not also erase the turn from learning and memory.
     *
     * Both fields are populated: AFTER_TURN hooks that encode the exchange (MemoryPlugin) bail
     * out on a null userInput or assistantReply, so an empty context silently drops the turn.
     */
    private suspend fun settle(sessionId: String, input: String, reply: String) {
        withContext(NonCancellable) {
            pluginRegistry.dispatch(
                HookPoint.AFTER_TURN,
                HookContext(sessionId, userInput = input, assistantReply = reply)
            )
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
            systemPrompt = listOfNotNull(config.systemPrompt, DefaultPrompt.UNATTENDED).joinToString("\n\n"),
            pluginRegistry = pluginRegistry
        )
    }
}
