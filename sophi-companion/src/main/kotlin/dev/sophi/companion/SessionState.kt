package dev.sophi.companion

import dev.sophi.core.tools.ConfirmationRequest

sealed class SessionState {
    object Idle : SessionState()
    object Running : SessionState()
    data class NeedsConfirmation(val requests: List<ConfirmationRequest>) : SessionState()
    data class Error(val message: String) : SessionState()
}
