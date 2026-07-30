package dev.sophi.core.tools

/**
 * Auto-mode gate: for each request, consult the tool's own ruleVerdict() first; only fall back
 * to the LLM classifier when the tool has no opinion (UNKNOWN). LOW_RISK auto-approves; anything
 * else (HIGH_RISK, or a missing tool) is forwarded to [fallback] for a real human decision,
 * batched together exactly as ConfirmationPolicy already batches a round's requests.
 */
class AutoModeConfirmationPolicy(
    private val registry: ToolRegistry,
    private val classifier: RiskClassifier,
    private val fallback: ConfirmationPolicy
) : ConfirmationPolicy {

    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> {
        val (autoApproved, needsHuman) = requests.partition { resolve(it) == RuleVerdict.LOW_RISK }
        val autoResult = autoApproved.associate { it.callId to true }
        val humanResult = if (needsHuman.isEmpty()) emptyMap() else fallback.confirm(needsHuman)
        return autoResult + humanResult
    }

    private suspend fun resolve(request: ConfirmationRequest): RuleVerdict {
        val tool = registry.getOrNull(request.toolName) ?: return RuleVerdict.HIGH_RISK
        val ruleVerdict = tool.ruleVerdict(request.argumentsJson)
        return if (ruleVerdict != RuleVerdict.UNKNOWN) ruleVerdict
        else classifier.classify(tool.name, tool.description, request.riskLevel, request.argumentsJson)
    }
}
