package dev.sophi.memory.jane

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class HabitAnalysisTest : FunSpec({
    test("circularHourConcentration: tight cluster at one hour returns that hour with full confidence") {
        val (hour, confidence) = circularHourConcentration(listOf(8, 8, 8))
        hour shouldBe 8
        confidence shouldBe (1.0 plusOrMinus 1e-9)
    }

    test("circularHourConcentration: wraps midnight correctly instead of averaging to midday") {
        val (hour, confidence) = circularHourConcentration(listOf(23, 0, 1))
        hour shouldBe 0
        confidence shouldBe (1.0 plusOrMinus 1e-9)
    }

    test("circularHourConcentration: scattered hours around the clock yield low confidence") {
        // Deliberately NOT evenly spaced (2, 6, 14, 20 have gaps 4/8/6/6, not 6/6/6/6) -- an
        // evenly-spaced set's unit vectors cancel to exactly zero, making atan2 unstable on
        // floating-point noise near the origin (verified: [1,7,13,19] flaked to an arbitrary
        // hour). This set's mean vector is well away from zero, so the result is deterministic.
        val (hour, confidence) = circularHourConcentration(listOf(2, 6, 14, 20))
        hour shouldBe 1
        confidence shouldBe (0.25 plusOrMinus 1e-9)
    }

    test("circularHourConcentration: empty input returns hour 0 with zero confidence") {
        circularHourConcentration(emptyList()) shouldBe (0 to 0.0)
    }

    test("modalDayConcentration: a dominant day returns it with its fraction") {
        val (day, confidence) = modalDayConcentration(listOf(1, 1, 1, 3))
        day shouldBe 1
        confidence shouldBe (0.75 plusOrMinus 1e-9)
    }

    test("modalDayConcentration: empty input returns Monday with zero confidence") {
        modalDayConcentration(emptyList()) shouldBe (1 to 0.0)
    }
})
