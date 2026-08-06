package dev.sophi.companion

sealed class SessionState {
    object Idle : SessionState()
    object Running : SessionState()
    object NeedsConfirmation : SessionState()
    data class Error(val message: String) : SessionState()
}
