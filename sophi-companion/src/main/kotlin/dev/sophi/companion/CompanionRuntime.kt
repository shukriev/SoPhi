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
    taskStore: TaskStore,
    runLog: RunLog,
    notifier: Notifier
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scheduleEngine = sophiRuntime.scheduleEngine(taskStore, runLog, notifier)
    private val sessionStates = mutableMapOf<String, MutableStateFlow<SessionState>>()
    private val sessionMessages = mutableMapOf<String, MutableStateFlow<List<String>>>()
    private var pollingJob: Job? = null

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
