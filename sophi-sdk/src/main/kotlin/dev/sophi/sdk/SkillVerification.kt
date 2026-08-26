package dev.sophi.sdk

enum class SkillVerificationRecommendation { PROMOTE, REVERT, MANUAL_REVIEW }

data class SkillVerificationResult(val recommendation: SkillVerificationRecommendation, val reason: String)

private const val BORDERLINE_FRACTION_OF_CAP = 0.5

/**
 * Pure function, no LLM/IO — mirrors Tournament.evaluateAcceptance's regression-safety principle
 * but without its improvement requirement: a skill only affects behavior when the model chooses to
 * invoke it, so it doesn't need to move the suite's aggregate score to be worth keeping.
 */
fun recommendFromScores(
    baselineScores: Map<String, List<Double>>,
    candidateScores: Map<String, List<Double>>,
    regressionCap: Double = 0.02
): SkillVerificationResult {
    val regressed = regressedCategories(baselineScores, candidateScores, regressionCap)
    if (regressed.isNotEmpty()) {
        return SkillVerificationResult(SkillVerificationRecommendation.REVERT, "category '${regressed.first()}' regressed beyond the cap")
    }
    val borderline = regressedCategories(baselineScores, candidateScores, regressionCap * BORDERLINE_FRACTION_OF_CAP)
    if (borderline.isNotEmpty()) {
        return SkillVerificationResult(
            SkillVerificationRecommendation.MANUAL_REVIEW,
            "category '${borderline.first()}' regressed more than half the cap, short of triggering an automatic revert"
        )
    }
    return SkillVerificationResult(SkillVerificationRecommendation.PROMOTE, "no category regressed beyond half the cap")
}
