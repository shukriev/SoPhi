package dev.sophi.companion

import dev.sophi.hub.HubEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Companion-side view of every CLI session currently connected to the embedded HubServer.
 * Same StateFlow-per-session shape as CompanionRuntime's own sessionStates, so SessionsTab/
 * ChatTab/PendingConfirmationsBanner can treat a remote id exactly like a local one.
 */
class RemoteSessionRegistry {
    private val states = mutableMapOf<String, MutableStateFlow<SessionState>>()
    private val titles = mutableMapOf<String, String?>()
    private val registered = mutableSetOf<String>()

    private fun stateFlow(sessionId: String): MutableStateFlow<SessionState> =
        states.getOrPut(sessionId) { MutableStateFlow(SessionState.Idle) }

    fun onEvent(event: HubEvent) {
        when (event) {
            is HubEvent.SessionRegistered -> {
                registered += event.sessionId
                titles[event.sessionId] = event.title
                stateFlow(event.sessionId) // ensure it exists, defaults to Idle
            }
            is HubEvent.SessionClosed -> {
                registered -= event.sessionId
            }
            is HubEvent.TurnStarted -> stateFlow(event.sessionId).value = SessionState.Running
            is HubEvent.TurnEnded -> stateFlow(event.sessionId).value = SessionState.Idle
            is HubEvent.ConfirmationRequested ->
                stateFlow(event.sessionId).value = SessionState.NeedsConfirmation(event.requests)
            is HubEvent.ConfirmationResolved -> stateFlow(event.sessionId).value = SessionState.Running
            is HubEvent.Token, is HubEvent.ReasoningToken,
            is HubEvent.ToolCallStarted, is HubEvent.ToolCallFinished -> Unit // status unaffected
        }
    }

    fun stateFlowFor(sessionId: String): StateFlow<SessionState> = stateFlow(sessionId)
    fun remoteSessionIds(): Set<String> = registered.toSet()
    fun titleFor(sessionId: String): String? = titles[sessionId]
}
