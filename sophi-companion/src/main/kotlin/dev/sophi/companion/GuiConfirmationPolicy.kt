package dev.sophi.companion

import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest

class GuiConfirmationPolicy(
    private val notify: (title: String, body: String) -> Unit,
    private val onConfirmationNeeded: suspend (List<ConfirmationRequest>) -> Map<String, Boolean>
) : ConfirmationPolicy {
    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> {
        val toolNames = requests.joinToString(", ") { it.toolName }
        notify("Sophi needs confirmation", "Approve running: $toolNames?")
        return onConfirmationNeeded(requests)
    }
}
