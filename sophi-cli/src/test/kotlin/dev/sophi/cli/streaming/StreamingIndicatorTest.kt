package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class StreamingIndicatorTest : FunSpec({
    test("renderSpinner for Generating includes token count and latency") {
        val phase = StreamingPhase.Generating(responseTokens = listOf("a", "b", "c", "d", "e"))
        Thread.sleep(50)
        val output = StreamingIndicator.renderSpinner(phase)
        output shouldContain "Generating"
        output shouldContain "5"
        output shouldContain "s"
    }

    test("renderSpinner for ExecutingTool includes tool name") {
        val phase = StreamingPhase.ExecutingTool(toolName = "read_file")
        val output = StreamingIndicator.renderSpinner(phase)
        output shouldContain "read_file"
        output shouldContain "🔧"
    }

    test("spinner animation cycles through frames") {
        val frames = StreamingIndicator.getAnimationFrames()
        frames.size shouldBe 10
    }

    test("renderError shows error state with token count") {
        val phase = StreamingPhase.Generating(responseTokens = listOf("a", "b", "c"))
        Thread.sleep(20)
        val output = StreamingIndicator.renderError(phase, 3)
        output shouldContain "Generation failed"
        output shouldContain "3 tokens"
    }
})
