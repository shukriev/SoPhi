package dev.sophi.infra

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BudgetTrackerTest : FunSpec({
    test("BudgetTracker record accumulates tokens") {
        val tracker = BudgetTracker(100)
        tracker.record(30)
        tracker.record(20)
        tracker.used() shouldBe 50
    }

    test("BudgetTracker record throws BudgetExceededException when limit exceeded") {
        val tracker = BudgetTracker(50)
        tracker.record(30)
        shouldThrow<BudgetExceededException> { tracker.record(25) }
    }

    test("BudgetTracker reset clears usage to zero") {
        val tracker = BudgetTracker(100)
        tracker.record(60)
        tracker.reset()
        tracker.used() shouldBe 0
    }

    test("BudgetTracker can record again after reset without throwing") {
        val tracker = BudgetTracker(100)
        tracker.record(90)
        tracker.reset()
        tracker.record(90)
        tracker.used() shouldBe 90
    }
})
