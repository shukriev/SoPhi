package dev.sophi.core.tools

fun interface ConfirmationPolicy {
    suspend fun confirm(toolName: String, argumentsJson: String): Boolean

    companion object {
        val ALLOW_ALL: ConfirmationPolicy = ConfirmationPolicy { _, _ -> true }
        val DENY_DESTRUCTIVE: ConfirmationPolicy = ConfirmationPolicy { _, _ -> false }
    }
}
