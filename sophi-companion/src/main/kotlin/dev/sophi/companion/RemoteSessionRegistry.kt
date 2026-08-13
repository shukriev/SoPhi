package dev.sophi.companion

import dev.sophi.hub.HubEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Companion-side view of every CLI session currently connected to the embedded HubServer.
 * Same StateFlow-per-session shape as CompanionRuntime's own sessionStates, so AppShell/
 * ChatTab can treat a remote id exactly like a local one.
 *
 * Drives a shared SessionTranscriptBuilder per session from the hub's streaming events (Token,
 * ReasoningToken, ToolCallStarted, ToolCallFinished) — the same events sophi-cli's terminal
 * renders live, mirrored here so a CLI session is visible in the companion too, not just its
 * Idle/Running/Error status. Also tracks lastActiveMillis from HubEvent.timestamp, updated on
 * every event — the real origin time, not a first-seen-by-companion proxy.
 */
class RemoteSessionRegistry {
    private val states = mutableMapOf<String, MutableStateFlow<SessionState>>()
    private val titles = mutableMapOf<String, String?>()
    private val registered = mutableSetOf<String>()
    private val transcriptBuilders = mutableMapOf<String, SessionTranscriptBuilder>()
    private val lastActiveMillis = mutableMapOf<String, Long>()

    private fun stateFlow(sessionId: String): MutableStateFlow<SessionState> =
        states.getOrPut(sessionId) { MutableStateFlow(SessionState.Idle) }

    private fun transcriptBuilderFor(sessionId: String): SessionTranscriptBuilder =
        transcriptBuilders.getOrPut(sessionId) { SessionTranscriptBuilder() }

    fun onEvent(event: HubEvent) {
        lastActiveMillis[event.sessionId] = event.timestamp
        when (event) {
            is HubEvent.SessionRegistered -> {
                registered += event.sessionId
                titles[event.sessionId] = event.title
                stateFlow(event.sessionId) // ensure it exists, defaults to Idle
                transcriptBuilderFor(event.sessionId) // ensure it exists, defaults to empty
            }
            is HubEvent.SessionClosed -> {
                registered -= event.sessionId
            }
            is HubEvent.TurnStarted -> {
                stateFlow(event.sessionId).value = SessionState.Running
                transcriptBuilderFor(event.sessionId).startTurn(event.userInput)
            }
            is HubEvent.TurnEnded -> {
                stateFlow(event.sessionId).value = SessionState.Idle
                transcriptBuilderFor(event.sessionId).endTurn()
            }
            is HubEvent.Token -> transcriptBuilderFor(event.sessionId).onToken(event.text)
            is HubEvent.ReasoningToken -> transcriptBuilderFor(event.sessionId).onReasoningToken(event.text)
            is HubEvent.ToolCallStarted ->
                transcriptBuilderFor(event.sessionId).onToolCallStarted(event.name, event.argsJson)
            is HubEvent.ToolCallFinished ->
                transcriptBuilderFor(event.sessionId).onToolCallFinished(event.name, event.result, event.isError)
            is HubEvent.ConfirmationRequested ->
                stateFlow(event.sessionId).value = SessionState.NeedsConfirmation(event.requests)
            is HubEvent.ConfirmationResolved -> stateFlow(event.sessionId).value = SessionState.Running
        }
    }

    fun stateFlowFor(sessionId: String): StateFlow<SessionState> = stateFlow(sessionId)
    fun transcriptFor(sessionId: String): StateFlow<List<TranscriptEntry>> = transcriptBuilderFor(sessionId).transcript
    fun remoteSessionIds(): Set<String> = registered.toSet()
    fun titleFor(sessionId: String): String? = titles[sessionId]
    fun lastActiveMillisFor(sessionId: String): Long = lastActiveMillis[sessionId] ?: 0L
}
