package dev.sophi.companion

import dev.sophi.sdk.SophiRuntime
import dev.sophi.schedule.notify.Notifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CompanionRuntime(
    private val sophiRuntime: SophiRuntime,
    val sessionManager: dev.sophi.core.session.SessionManager,
    private val mcpConfigPath: java.nio.file.Path,
    taskStore: TaskStore,
    runLog: RunLog,
    notifier: Notifier
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scheduleEngine = sophiRuntime.scheduleEngine(taskStore, runLog, notifier)
    private val sessionStates = mutableMapOf<String, MutableStateFlow<SessionState>>()
    private val sessionMessages = mutableMapOf<String, MutableStateFlow<List<String>>>()
    private var pollingJob: Job? = null
    private val mcpConfigLoader = dev.sophi.mcp.config.McpConfigLoader()
    private val mcpConfigWriter = dev.sophi.mcp.config.McpConfigWriter()

    init {
        scope.launch {
            mcpServers().filter { it.enabled }.forEach { config ->
                runCatching { sophiRuntime.connectMcpServer(config) }
            }
        }
    }

    fun mcpServers(): List<dev.sophi.mcp.config.McpServerConfig> = mcpConfigLoader.load(mcpConfigPath).servers

    suspend fun addOrUpdateMcpServer(config: dev.sophi.mcp.config.McpServerConfig) {
        val current = mcpConfigLoader.load(mcpConfigPath)
        val updated = current.servers.filterNot { it.name == config.name } + config
        mcpConfigWriter.write(mcpConfigPath, current.copy(servers = updated))
        sophiRuntime.disconnectMcpServer(config.name)
        if (config.enabled) sophiRuntime.connectMcpServer(config)
    }

    suspend fun removeMcpServer(name: String) {
        val current = mcpConfigLoader.load(mcpConfigPath)
        mcpConfigWriter.write(mcpConfigPath, current.copy(servers = current.servers.filterNot { it.name == name }))
        sophiRuntime.disconnectMcpServer(name)
    }

    suspend fun setMcpServerEnabled(name: String, enabled: Boolean) {
        val current = mcpConfigLoader.load(mcpConfigPath)
        val config = current.servers.find { it.name == name } ?: return
        val updated = config.copy(enabled = enabled)
        mcpConfigWriter.write(mcpConfigPath, current.copy(servers = current.servers.map { if (it.name == name) updated else it }))
        if (enabled) sophiRuntime.connectMcpServer(updated) else sophiRuntime.disconnectMcpServer(name)
    }

    private fun stateFlowFor(sessionId: String): MutableStateFlow<SessionState> =
        sessionStates.getOrPut(sessionId) { MutableStateFlow(SessionState.Idle) }

    private fun messagesFlowFor(sessionId: String): MutableStateFlow<List<String>> =
        sessionMessages.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

    fun sessionState(sessionId: String): StateFlow<SessionState> = stateFlowFor(sessionId)

    /** Chat lines for a session, in order — each is prefixed "you: " or "sophi: ". */
    fun sessionMessages(sessionId: String): StateFlow<List<String>> = messagesFlowFor(sessionId)

    suspend fun newSession(title: String? = null): String = sophiRuntime.newSession(title)

    fun sendMessage(sessionId: String, input: String) {
        val state = stateFlowFor(sessionId)
        val messages = messagesFlowFor(sessionId)
        messages.value = messages.value + "you: $input"
        state.value = SessionState.Running
        scope.launch {
            try {
                val reply = sophiRuntime.turn(sessionId, input)
                messages.value = messages.value + "sophi: $reply"
                state.value = SessionState.Idle
            } catch (e: Exception) {
                state.value = SessionState.Error(e.message ?: "unknown error")
            }
        }
    }

    fun startSchedulePolling(intervalMs: Long = 30_000) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                scheduleEngine.tickOnce()
                delay(intervalMs)
            }
        }
    }

    fun close() {
        pollingJob?.cancel()
        scope.cancel()
        sophiRuntime.close()
    }
}
