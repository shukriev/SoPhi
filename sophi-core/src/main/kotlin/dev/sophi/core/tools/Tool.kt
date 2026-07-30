package dev.sophi.core.tools

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
    suspend fun execute(argumentsJson: String): String
}
