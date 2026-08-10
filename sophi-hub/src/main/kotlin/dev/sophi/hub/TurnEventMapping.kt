package dev.sophi.hub

import dev.sophi.core.agent.TurnEvent

/**
 * Maps sophi-core's per-token/tool-call streaming events onto the wire protocol. Confirmation
 * events have no case here — RemoteAwareConfirmationPolicy (sophi-cli) publishes
 * HubEvent.ConfirmationRequested/Resolved directly, since it needs to attach the actual
 * ConfirmationRequest list, which TurnEvent.ConfirmationStarted doesn't carry.
 */
fun TurnEvent.toHubEvent(sessionId: String): HubEvent? = when (this) {
    is TurnEvent.Token -> HubEvent.Token(sessionId, text)
    is TurnEvent.ReasoningToken -> HubEvent.ReasoningToken(sessionId, text)
    is TurnEvent.ToolCallStarted -> HubEvent.ToolCallStarted(sessionId, name, argsJson)
    is TurnEvent.ToolCallFinished -> HubEvent.ToolCallFinished(sessionId, name, result, isError)
    is TurnEvent.ConfirmationStarted -> null
    TurnEvent.ConfirmationFinished -> null
}
