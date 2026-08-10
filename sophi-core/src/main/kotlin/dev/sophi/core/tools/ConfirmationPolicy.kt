package dev.sophi.core.tools

@kotlinx.serialization.Serializable
data class ConfirmationRequest(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val riskLevel: RiskLevel
)

fun interface ConfirmationPolicy {
    suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean>

    companion object {
        val ALLOW_ALL: ConfirmationPolicy = ConfirmationPolicy { requests -> requests.associate { it.callId to true } }
        val DENY_ALL: ConfirmationPolicy = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
    }
}
