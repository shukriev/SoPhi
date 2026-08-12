package dev.sophi.cli

import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.hub.HubClient
import dev.sophi.hub.HubCommand
import dev.sophi.hub.HubEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/**
 * Races the terminal's y/N prompt against a remote answer from the companion (over [hubClient]),
 * first response wins — matches how a shared tmux session behaves, no lock/hand-off state
 * machine. If [hubClient] is null (--no-remote, or the hub was never reachable), this is a thin
 * pass-through to [terminal].
 */
/**
 * [hubClient] and [sessionId] are suppliers, not values: both are only read once a confirmation
 * is actually requested, which is long after construction. That matters because the session — and
 * therefore the hub client keyed to it — cannot exist until the runtime that owns the
 * SessionManager has been built, and building that runtime requires this policy.
 */
class RemoteAwareConfirmationPolicy(
    private val terminal: ConfirmationPolicy,
    private val hubClient: () -> HubClient?,
    private val sessionId: () -> String
) : ConfirmationPolicy {
    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> {
        val client = hubClient() ?: return terminal.confirm(requests)
        val sessionId = sessionId()
        val callIds = requests.map { it.callId }.toSet()
        client.publish(HubEvent.ConfirmationRequested(sessionId, requests))
        try {
            return coroutineScope {
                val terminalAnswer = async { terminal.confirm(requests) }
                val remoteAnswer = async {
                    client.commands
                        .filterIsInstance<HubCommand.ConfirmationResponse>()
                        .filter { it.sessionId == sessionId && it.callId in callIds }
                        .first()
                }
                select {
                    terminalAnswer.onAwait { answer ->
                        remoteAnswer.cancel()
                        answer
                    }
                    remoteAnswer.onAwait { response ->
                        terminalAnswer.cancel()
                        requests.associate { it.callId to response.approved }
                    }
                }
            }
        } finally {
            client.publish(HubEvent.ConfirmationResolved(sessionId))
        }
    }
}
