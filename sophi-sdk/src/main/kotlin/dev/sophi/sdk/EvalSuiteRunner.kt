package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.eval.EvalCase
import dev.sophi.core.agent.eval.runEvalScenario
import dev.sophi.core.agent.plan.PlanFinalStatus
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.versioning.Scorecard
import dev.sophi.versioning.ScorecardStore

/** Re-run budget for a case whose result needs confirming — see [runSuite]'s quarantine logic. */
private const val QUARANTINE_CHECK_RUNS = 3

private data class CaseResult(val case: EvalCase, val passed: Boolean, val quarantined: Boolean)

private suspend fun runCase(
    case: EvalCase,
    provider: LLMProvider,
    registry: ToolRegistry,
    sessionManager: SessionManager,
    contextWindowTokens: Int,
    model: String,
    systemPrompt: String?
): Boolean {
    val outcome = runEvalScenario(
        provider = provider, registry = registry, sessionManager = sessionManager,
        contextWindowTokens = contextWindowTokens, model = model, scenario = case.scenario,
        systemPrompt = systemPrompt
    )
    return outcome.finalStatus == PlanFinalStatus.Met
}

/**
 * Runs every case in [cases] once, then re-runs (up to [QUARANTINE_CHECK_RUNS] total) any case
 * that failed on its first attempt, to distinguish a genuinely-failing case from a flaky one.
 * A case whose repeated runs disagree is quarantined: recorded, but excluded from
 * [Scorecard.headlineScore] and [Scorecard.perCategory] until it passes consistently again.
 */
suspend fun runSuite(
    cases: List<EvalCase>,
    provider: LLMProvider,
    registry: ToolRegistry,
    sessionManager: SessionManager,
    contextWindowTokens: Int,
    model: String,
    configVersionId: String,
    systemPrompt: String?,
    scorecardStore: ScorecardStore
): Scorecard {
    val results = mutableListOf<CaseResult>()
    for (case in cases) {
        val first = runCase(case, provider, registry, sessionManager, contextWindowTokens, model, systemPrompt)
        if (first) {
            results.add(CaseResult(case, passed = true, quarantined = false))
        } else {
            val repeats = mutableListOf<Boolean>()
            repeat(QUARANTINE_CHECK_RUNS - 1) {
                repeats.add(runCase(case, provider, registry, sessionManager, contextWindowTokens, model, systemPrompt))
            }
            val allAgree = repeats.all { it == first }
            results.add(CaseResult(case, passed = first, quarantined = !allAgree))
        }
    }

    val counted = results.filterNot { it.quarantined }
    val headlineScore = if (counted.isEmpty()) 0.0 else counted.count { it.passed }.toDouble() / counted.size
    val perCategory = counted.groupBy { it.case.category }
        .mapValues { (_, inCategory) -> inCategory.count { it.passed }.toDouble() / inCategory.size }
    val quarantinedCaseIds = results.filter { it.quarantined }.map { it.case.id }

    return scorecardStore.record(
        configVersionId = configVersionId, headlineScore = headlineScore,
        perCategory = perCategory, quarantinedCaseIds = quarantinedCaseIds, totalCases = cases.size
    )
}
