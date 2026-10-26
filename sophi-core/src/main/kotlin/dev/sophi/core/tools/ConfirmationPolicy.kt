package dev.sophi.core.tools

import dev.sophi.core.audit.noteDecision
import dev.sophi.core.permissions.PermissionRule

@kotlinx.serialization.Serializable
data class ConfirmationRequest(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val riskLevel: RiskLevel,
    val preview: String? = null,
    /** The rules "always allow" would write for this call; null = don't offer "always". */
    val alwaysAllow: List<PermissionRule>? = null
)

fun interface ConfirmationPolicy {
    suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean>

    companion object {
        val ALLOW_ALL: ConfirmationPolicy = ConfirmationPolicy { requests -> requests.associate { noteDecision(it.callId, "allow-all"); it.callId to true } }
        val DENY_ALL: ConfirmationPolicy = ConfirmationPolicy { requests -> requests.associate { noteDecision(it.callId, "deny-all"); it.callId to false } }
    }
}
