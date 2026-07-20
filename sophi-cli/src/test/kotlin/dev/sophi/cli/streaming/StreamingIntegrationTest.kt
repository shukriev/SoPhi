package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class StreamingIntegrationTest : FunSpec({
    test("full streaming flow: spinner -> toggle -> token view") {
        val phase = StreamingPhase.Generating(tokenCount = 4, startTime = java.time.Instant.now())

        val spinner = StreamingIndicator.renderSpinner(phase, frameIndex = 0)
        spinner.shouldContain("Generating")
        spinner.shouldContain("4 tokens")

        var toggleState = TokenViewToggleState()
        toggleState = toggleState.toggle()
        toggleState.isViewingTokens shouldBe true

        val tokenStream = TokenStreamFormatter.renderTokenStream(phase, "The answer is...")
        tokenStream.shouldContain("The answer is...")
        tokenStream.shouldContain("4 tokens")

        toggleState = toggleState.toggle()
        toggleState.isViewingTokens shouldBe false
    }

    test("spinner animation cycles smoothly") {
        val timer = AnimationTimer(frameCount = 10)
        val frames = mutableListOf<Int>()

        repeat(15) {
            frames.add(timer.nextFrame())
        }

        frames.take(10) shouldBe (0..9).toList()
        frames.drop(10) shouldBe (0..4).toList()
    }

    test("tool execution indicator") {
        val toolPhase = StreamingPhase.ExecutingTool(
            toolName = "read_file",
            startTime = java.time.Instant.now()
        )

        Thread.sleep(25)
        val indicator = StreamingIndicator.renderSpinner(toolPhase, frameIndex = 2)

        indicator.shouldContain("🔧")
        indicator.shouldContain("read_file")
        indicator.shouldContain("s")
    }

    test("error state display") {
        val phase = StreamingPhase.Generating(tokenCount = 5, startTime = java.time.Instant.now())

        Thread.sleep(10)
        val error = StreamingIndicator.renderError(phase, 5)

        error.shouldContain("❌")
        error.shouldContain("Generation failed")
        error.shouldContain("5 tokens")
    }
})
