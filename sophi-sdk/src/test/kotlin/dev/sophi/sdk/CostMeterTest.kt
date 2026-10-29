package dev.sophi.sdk

import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun info(i: Double?, p: Double?, r: Double? = null) =
    ModelInfo("anthropic", "m", 200_000, null, i, p, r, tools = true, reasoning = false, vision = false, baseModel = null)

class CostMeterTest : FunSpec({
    test("cost uses input, cache-read and output prices") {
        CostMeter.cost(info(3.0, 15.0, 0.3), TokenUsage(1_000_000, 100_000, 400_000)) shouldBe (3.42 plusOrMinus 1e-9)
    }

    test("no cache-read price falls back to input price") {
        CostMeter.cost(info(3.0, 15.0), TokenUsage(1_000_000, 0, 400_000)) shouldBe (3.0 plusOrMinus 1e-9)
    }

    test("an unpriced model costs nothing and never trips the budget") {
        val meter = CostMeter({ null }, maxUsd = 0.0)
        repeat(3) { meter.record("x", TokenUsage(1000, 1000)) }
        meter.totalUsd shouldBe 0.0
        meter.overBudget() shouldBe null
    }

    test("overBudget names the total and the budget") {
        val meter = CostMeter({ info(3.0, 15.0, 0.3) }, maxUsd = 3.0)
        meter.overBudget() shouldBe null
        meter.record("m", TokenUsage(1_000_000, 100_000, 400_000))
        val msg = meter.overBudget()!!
        msg shouldContain "$3.42"
        msg shouldContain "$3.00"
    }
})
