package dev.sophi.core.tools

/**
 * Auto-approves any request below [minRiskToPrompt], forwarding the rest to [fallback] batched
 * together exactly as ConfirmationPolicy already batches a round's requests -- same shape as
 * AutoModeConfirmationPolicy's split, but a plain risk-tier threshold instead of a classifier
 * verdict. Lets a caller express "only ask me above X" (e.g. minRiskToPrompt = DESTRUCTIVE means
 * only delete/write-type actions ever prompt).
 */
class ThresholdConfirmationPolicy(
    private val minRiskToPrompt: RiskLevel,
    private val fallback: ConfirmationPolicy
) : ConfirmationPolicy {

    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> {
        val (autoApproved, needsHuman) = requests.partition { it.riskLevel.ordinal < minRiskToPrompt.ordinal }
        val autoResult = autoApproved.associate { it.callId to true }
        val humanResult = if (needsHuman.isEmpty()) emptyMap() else fallback.confirm(needsHuman)
        return autoResult + humanResult
    }
}
