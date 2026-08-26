package dev.sophi.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SkillVerificationTest : FunSpec({
    test("no regression at all recommends PROMOTE") {
        val baseline = mapOf("overall" to listOf(0.80), "coding" to listOf(0.90))
        val candidate = mapOf("overall" to listOf(0.80), "coding" to listOf(0.90))

        recommendFromScores(baseline, candidate).recommendation shouldBe SkillVerificationRecommendation.PROMOTE
    }

    test("a category regressing beyond the cap recommends REVERT") {
        val baseline = mapOf("overall" to listOf(0.80), "coding" to listOf(0.90))
        val candidate = mapOf("overall" to listOf(0.80), "coding" to listOf(0.50))

        recommendFromScores(baseline, candidate, regressionCap = 0.02).recommendation shouldBe SkillVerificationRecommendation.REVERT
    }

    test("a category regressing more than half the cap but not past it recommends MANUAL_REVIEW") {
        val baseline = mapOf("overall" to listOf(0.80), "coding" to listOf(0.90))
        val candidate = mapOf("overall" to listOf(0.80), "coding" to listOf(0.885)) // regressed 0.015, cap=0.02, half-cap=0.01

        recommendFromScores(baseline, candidate, regressionCap = 0.02).recommendation shouldBe SkillVerificationRecommendation.MANUAL_REVIEW
    }

    test("a category regressing less than half the cap recommends PROMOTE") {
        val baseline = mapOf("overall" to listOf(0.80), "coding" to listOf(0.90))
        val candidate = mapOf("overall" to listOf(0.80), "coding" to listOf(0.895)) // regressed 0.005, half-cap=0.01

        recommendFromScores(baseline, candidate, regressionCap = 0.02).recommendation shouldBe SkillVerificationRecommendation.PROMOTE
    }

    test("an improvement with no regression still recommends PROMOTE (no improvement requirement)") {
        val baseline = mapOf("overall" to listOf(0.50), "coding" to listOf(0.50))
        val candidate = mapOf("overall" to listOf(0.50), "coding" to listOf(0.50))

        recommendFromScores(baseline, candidate).recommendation shouldBe SkillVerificationRecommendation.PROMOTE
    }
})
