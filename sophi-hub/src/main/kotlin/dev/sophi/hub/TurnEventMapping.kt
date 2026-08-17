package dev.sophi.hub

import dev.sophi.core.agent.TurnEvent

/**
 * Maps sophi-core's per-token/tool-call streaming events onto the wire protocol. Confirmation
 * events have no case here — RemoteAwareConfirmationPolicy (sophi-cli) publishes
 * HubEvent.ConfirmationRequested/Resolved directly, since it needs to attach the actual
 * ConfirmationRequest list, which TurnEvent.ConfirmationStarted doesn't carry.
 *
 * [timestamp] defaults to now — the production call site (SophiCli.kt) never passes it
 * explicitly; it exists as a parameter purely so tests can assert on a fixed, known value
 * instead of two independently-evaluated System.currentTimeMillis() calls racing each other.
 */
fun TurnEvent.toHubEvent(sessionId: String, timestamp: Long = System.currentTimeMillis()): HubEvent? = when (this) {
    is TurnEvent.Token -> HubEvent.Token(sessionId, text, timestamp)
    is TurnEvent.ReasoningToken -> HubEvent.ReasoningToken(sessionId, text, timestamp)
    is TurnEvent.ToolCallStarted -> HubEvent.ToolCallStarted(sessionId, name, argsJson, timestamp)
    is TurnEvent.ToolCallFinished -> HubEvent.ToolCallFinished(sessionId, name, result, isError, timestamp)
    is TurnEvent.ConfirmationStarted -> null
    TurnEvent.ConfirmationFinished -> null
}
