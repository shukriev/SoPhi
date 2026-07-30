package dev.sophi.core.tools

/**
 * Lets a surface flip auto mode on/off at runtime (e.g. a CLI slash command) without
 * reconstructing AgentLoop — AgentLoop holds one ConfirmationPolicy reference for the whole
 * session; this wrapper just changes which inner policy that reference delegates to.
 */
class ToggleableConfirmationPolicy(
    private val autoPolicy: ConfirmationPolicy,
    private val manualPolicy: ConfirmationPolicy,
    @Volatile var autoModeEnabled: Boolean
) : ConfirmationPolicy {
    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> =
        (if (autoModeEnabled) autoPolicy else manualPolicy).confirm(requests)
}
