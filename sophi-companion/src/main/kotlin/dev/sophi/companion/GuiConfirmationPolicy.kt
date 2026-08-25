package dev.sophi.companion

import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest
import kotlin.coroutines.coroutineContext

class GuiConfirmationPolicy(
    private val notify: (title: String, body: String) -> Unit,
    private val onConfirmationNeeded: suspend (sessionId: String, requests: List<ConfirmationRequest>) -> Map<String, Boolean>
) : ConfirmationPolicy {
    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> {
        val toolNames = requests.joinToString(", ") { it.toolName }
        notify("Sophi needs confirmation", "Approve running: $toolNames?")
        val sessionId = coroutineContext[SessionIdContext]?.sessionId
            ?: return requests.associate { it.callId to false }
        return onConfirmationNeeded(sessionId, requests)
    }
}
