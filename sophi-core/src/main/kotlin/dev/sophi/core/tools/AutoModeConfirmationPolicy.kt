package dev.sophi.core.tools

import dev.sophi.core.audit.noteDecision

/**
 * Auto-mode gate: for each request, consult the tool's own ruleVerdict() first; only fall back
 * to the LLM classifier when the tool has no opinion (UNKNOWN). LOW_RISK auto-approves; anything
 * else (HIGH_RISK, or a missing tool) is forwarded to [fallback] for a real human decision,
 * batched together exactly as ConfirmationPolicy already batches a round's requests.
 * [classifierRule] is the audit rule noted when [classifier] approves (god mode, whose classifier approves everything, says "allow-all").
 */
class AutoModeConfirmationPolicy(
    private val registry: ToolRegistry,
    private val classifier: RiskClassifier,
    private val fallback: ConfirmationPolicy,
    private val classifierRule: String = "auto:classifier",
) : ConfirmationPolicy {

    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> {
        val needsHuman = mutableListOf<ConfirmationRequest>()
        val autoResult = mutableMapOf<String, Boolean>()
        for (request in requests) {
            val rule = resolve(request)
            if (rule == null) needsHuman += request
            else { noteDecision(request.callId, rule); autoResult[request.callId] = true }
        }
        val humanResult = if (needsHuman.isEmpty()) emptyMap() else fallback.confirm(needsHuman)
        return autoResult + humanResult
    }

    /** The rule that auto-approved [request] ("auto:rule" or [classifierRule]), or null when a human must decide. */
    private suspend fun resolve(request: ConfirmationRequest): String? {
        val tool = registry.getOrNull(request.toolName) ?: return null
        val ruleVerdict = tool.ruleVerdict(request.argumentsJson)
        return when {
            ruleVerdict != RuleVerdict.UNKNOWN -> "auto:rule".takeIf { ruleVerdict == RuleVerdict.LOW_RISK }
            classifier.classify(tool.name, tool.description, request.riskLevel, request.argumentsJson) == RuleVerdict.LOW_RISK -> classifierRule
            else -> null
        }
    }
}
