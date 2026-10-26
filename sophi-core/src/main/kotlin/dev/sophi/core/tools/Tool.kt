package dev.sophi.core.tools

import dev.sophi.core.permissions.PermissionSubject

@kotlinx.serialization.Serializable
enum class RiskLevel { SAFE, CAUTION, DESTRUCTIVE }

enum class RuleVerdict { LOW_RISK, HIGH_RISK, UNKNOWN }

interface Tool {
    val name: String
    val description: String
    val parametersJson: String
    /**
     * True for a tool whose schema stays out of the prompt until the model loads it with
     * `tool_search` (see [ToolRegistry.definitions]). Only honoured while `tool_search` is registered.
     */
    val deferred: Boolean get() = false
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
    /**
     * What permission rule patterns match for this call (bash parts, paths, URLs). The default, an empty list,
     * means the tool has no subjects, so only tool-wide rules apply. Null: a subject-based tool could not read
     * the call (unparseable arguments, nothing to match); never an empty list in that case.
     */
    fun permissionSubjects(argumentsJson: String): List<PermissionSubject>? = emptyList()
    suspend fun execute(argumentsJson: String): String
}
