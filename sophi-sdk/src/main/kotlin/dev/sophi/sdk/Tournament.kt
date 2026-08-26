package dev.sophi.sdk

import kotlin.math.sqrt

/** A challenger scoring more than this far above the incumbent is flagged suspect regardless of
 *  statistical significance — the suite/grader is part of what can be gamed. */
private const val REWARD_HACKING_JUMP_THRESHOLD = 0.10

data class TournamentResult(val accepted: Boolean, val reason: String, val requiresManualReview: Boolean)

private fun mean(xs: List<Double>): Double = xs.average()

private fun stddev(xs: List<Double>): Double {
    val m = mean(xs)
    return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
}

/**
 * Pure function, no LLM/IO — deterministic math over repeated-run score samples. [incumbentScores]
 * and [challengerScores] map category name to that category's per-run scores; both must include an
 * `"overall"` entry (the headline score across repeated runs). A category present only in one map
 * is ignored for the regression check — there's nothing to compare it against.
 */
fun evaluateAcceptance(
    incumbentScores: Map<String, List<Double>>,
    challengerScores: Map<String, List<Double>>,
    perCategoryRegressionCap: Double = 0.02
): TournamentResult {
    val incumbentOverall = incumbentScores.getValue("overall")
    val challengerOverall = challengerScores.getValue("overall")
    val noiseFloor = stddev(incumbentOverall)
    val improvement = mean(challengerOverall) - mean(incumbentOverall)
    val requiresManualReview = improvement > REWARD_HACKING_JUMP_THRESHOLD

    if (improvement <= noiseFloor) {
        return TournamentResult(
            accepted = false, reason = "improvement did not exceed the noise floor",
            requiresManualReview = requiresManualReview
        )
    }

    for ((category, challengerCategoryScores) in challengerScores) {
        if (category == "overall") continue
        val incumbentCategoryScores = incumbentScores[category] ?: continue
        val regression = mean(incumbentCategoryScores) - mean(challengerCategoryScores)
        if (regression > perCategoryRegressionCap) {
            return TournamentResult(
                accepted = false, reason = "category '$category' regressed beyond the cap",
                requiresManualReview = requiresManualReview
            )
        }
    }

    return TournamentResult(
        accepted = true, reason = "improvement exceeded the noise floor with no category regression",
        requiresManualReview = requiresManualReview
    )
}
