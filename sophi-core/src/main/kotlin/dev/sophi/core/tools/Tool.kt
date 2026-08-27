package dev.sophi.core.tools

@kotlinx.serialization.Serializable
enum class RiskLevel { SAFE, CAUTION, DESTRUCTIVE }

enum class RuleVerdict { LOW_RISK, HIGH_RISK, UNKNOWN }

interface Tool {
    val name: String
    val description: String
    val parametersJson: String
    fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.SAFE
    /**
     * Fine-grained risk hint consulted only for calls that already cleared the SAFE bar (i.e.
     * riskLevel() returned CAUTION or DESTRUCTIVE) — used by auto mode to decide whether a
     * specific call is still boring enough to skip a confirmation prompt. UNKNOWN (the default)
     * means "no opinion, fall back to the LLM classifier."
     */
    fun ruleVerdict(argumentsJson: String): RuleVerdict = RuleVerdict.UNKNOWN
    /**
     * Optional human-readable preview shown instead of raw [argumentsJson] at confirmation time —
     * e.g. a diff or a content summary. Returning null (the default) keeps today's raw-JSON display.
     */
    fun confirmationPreview(argumentsJson: String): String? = null
    suspend fun execute(argumentsJson: String): String
}
