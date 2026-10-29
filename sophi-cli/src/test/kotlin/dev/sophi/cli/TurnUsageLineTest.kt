package dev.sophi.cli

import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.TurnEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun round(input: Int, output: Int, millis: Long = 0) = TurnEvent.RoundUsage(TokenUsage(input, output), millis)

class TurnUsageLineTest : FunSpec({
    val rounds = listOf(round(9_000, 300, 4_000), round(12_345, 520, 6_000))

    test("cloud, priced") {
        turnUsageLine("claude-x", rounds, cloud = true, turnUsd = 0.04123, sessionUsd = 0.3141, maxUsd = null) shouldBe
            "claude-x · 12.3k in · 820 out · $0.0412 this turn · $0.31 session"
    }

    test("cloud, priced, with a budget") {
        turnUsageLine("claude-x", rounds, cloud = true, turnUsd = 0.04123, sessionUsd = 0.3141, maxUsd = 5.0) shouldBe
            "claude-x · 12.3k in · 820 out · $0.0412 this turn · $0.31 session of $5.00"
    }

    test("cloud, unpriced") {
        turnUsageLine("x", rounds, cloud = true, turnUsd = null, sessionUsd = 0.0, maxUsd = null) shouldBe
            "x · 12.3k in · 820 out · price unknown"
    }

    test("local shows tokens per second") {
        turnUsageLine("qwen3-8b", rounds, cloud = false, turnUsd = null, sessionUsd = 0.0, maxUsd = null) shouldBe
            "qwen3-8b · 12.3k in · 820 out · 82 tok/s"
    }

    test("tok/s omitted with no timing") {
        turnUsageLine("qwen3-8b", listOf(round(800, 20)), cloud = false, turnUsd = null, sessionUsd = 0.0, maxUsd = null) shouldBe
            "qwen3-8b · 800 in · 20 out"
    }

    test("empty rounds is empty") {
        turnUsageLine("m", emptyList(), cloud = true, turnUsd = 0.0, sessionUsd = 0.0, maxUsd = null) shouldBe ""
    }
})
