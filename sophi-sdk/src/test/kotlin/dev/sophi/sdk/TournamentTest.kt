package dev.sophi.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class TournamentTest : FunSpec({
    test("format() reports challenger id, accept/reject reason, and a promote hint when accepted") {
        val outcome = TournamentRunResult(
            "ver_123",
            TournamentResult(accepted = true, reason = "clear win", requiresManualReview = false)
        )

        val text = format(outcome)

        text shouldContain "challenger version: ver_123"
        text shouldContain "accepted=true  reason=clear win"
        text shouldContain "sophi tournament promote ver_123"
    }

    test("format() omits the promote hint and flags MANUAL REVIEW REQUIRED when rejected with a suspicious jump") {
        val outcome = TournamentRunResult(
            "ver_9",
            TournamentResult(accepted = false, reason = "suspiciously large jump", requiresManualReview = true)
        )

        val text = format(outcome)

        text shouldContain "accepted=false  reason=suspiciously large jump"
        text shouldContain "MANUAL REVIEW REQUIRED"
        (text.contains("promote")) shouldBe false
    }

    test("a challenger scoring within the incumbent's noise floor is rejected") {
        val incumbentScores = mapOf("overall" to listOf(0.80, 0.82, 0.79))
        val challengerScores = mapOf("overall" to listOf(0.81, 0.83, 0.80))

        val result = evaluateAcceptance(incumbentScores, challengerScores)

        result.accepted shouldBe false
    }

    test("a challenger improving beyond the noise floor with no category regression is accepted") {
        val incumbentScores = mapOf("overall" to listOf(0.70, 0.71, 0.69))
        val challengerScores = mapOf("overall" to listOf(0.77, 0.78, 0.76)) // +7%, clears noise floor, under the 10% reward-hacking threshold

        val result = evaluateAcceptance(incumbentScores, challengerScores)

        result.accepted shouldBe true
        result.requiresManualReview shouldBe false
    }

    test("a challenger jumping more than 10% is flagged for mandatory manual review even when accepted") {
        val incumbentScores = mapOf("overall" to listOf(0.50, 0.51, 0.49))
        val challengerScores = mapOf("overall" to listOf(0.95, 0.96, 0.94))

        val result = evaluateAcceptance(incumbentScores, challengerScores)

        result.requiresManualReview shouldBe true
    }

    test("an overall improvement is rejected if any category regresses beyond the cap") {
        val incumbentScores = mapOf("overall" to listOf(0.60, 0.61, 0.59), "coding" to listOf(0.90, 0.91, 0.89))
        val challengerScores = mapOf("overall" to listOf(0.80, 0.81, 0.79), "coding" to listOf(0.50, 0.51, 0.49))

        val result = evaluateAcceptance(incumbentScores, challengerScores, perCategoryRegressionCap = 0.02)

        result.accepted shouldBe false
        result.reason shouldBe "category 'coding' regressed beyond the cap"
    }

    test("a category regression within the cap does not block an otherwise-accepted overall improvement") {
        val incumbentScores = mapOf("overall" to listOf(0.60, 0.61, 0.59), "coding" to listOf(0.90, 0.905, 0.895))
        val challengerScores = mapOf("overall" to listOf(0.80, 0.81, 0.79), "coding" to listOf(0.895, 0.90, 0.885))

        val result = evaluateAcceptance(incumbentScores, challengerScores, perCategoryRegressionCap = 0.02)

        result.accepted shouldBe true
    }

    test("regressedCategories returns only categories that regressed beyond the cap") {
        val baseline = mapOf("overall" to listOf(0.80), "coding" to listOf(0.90), "writing" to listOf(0.70))
        val challenger = mapOf("overall" to listOf(0.85), "coding" to listOf(0.50), "writing" to listOf(0.69))

        val regressed = regressedCategories(baseline, challenger, cap = 0.02)

        regressed shouldBe listOf("coding")
    }
})
