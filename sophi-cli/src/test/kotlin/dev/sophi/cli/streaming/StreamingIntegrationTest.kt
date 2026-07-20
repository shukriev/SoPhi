package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class StreamingIntegrationTest : FunSpec({
    test("full streaming flow: spinner -> toggle -> token view") {
        // Simulate a turn generating tokens
        val phase = StreamingPhase.Generating(
            thinkingTokens = listOf("Let me think", " about this..."),
            responseTokens = listOf("The answer", " is..."),
            startTime = java.time.Instant.now()
        )

        // User sees spinner initially
        val spinner = StreamingIndicator.renderSpinner(phase, frameIndex = 0)
        spinner.shouldContain("Generating")
        spinner.shouldContain("4 tokens") // 2 thinking + 2 response

        // User presses T to toggle
        var toggleState = TokenViewToggleState()
        toggleState = toggleState.toggle()
        toggleState.isViewingTokens shouldBe true

        // Now show token stream
        val tokenStream = TokenStreamFormatter.renderTokenStream(phase)
        tokenStream.shouldContain("[thinking]")
        tokenStream.shouldContain("[response]")

        // User presses T again to exit token view
        toggleState = toggleState.toggle()
        toggleState.isViewingTokens shouldBe false
    }

    test("spinner animation cycles smoothly") {
        val timer = AnimationTimer(frameCount = 10)
        val frames = mutableListOf<Int>()

        repeat(15) {
            frames.add(timer.nextFrame())
        }

        // Should cycle: 0,1,2...9,0,1,2...9,0,1,2...
        frames.take(10) shouldBe (0..9).toList()
        frames.drop(10) shouldBe (0..4).toList()
    }

    test("thinking token detection for Claude provider") {
        ThinkingTokenDetector.reset()

        val tokens = listOf(
            "<thinking>",
            "I need to solve",
            " this step by step",
            "</thinking>",
            "The solution is",
            " clear"
        )

        val results = mutableListOf<Pair<Boolean, String>>()
        for (token in tokens) {
            results.add(ThinkingTokenDetector.parseThinkingBlock(token))
        }

        // Check transitions: not thinking -> thinking -> response
        results[0].first shouldBe true  // <thinking>
        results[1].first shouldBe true  // inside thinking
        results[3].first shouldBe false // </thinking>
        results[4].first shouldBe false // after thinking
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
        val phase = StreamingPhase.Generating(
            responseTokens = listOf("a", "b", "c", "d", "e"),
            startTime = java.time.Instant.now()
        )

        Thread.sleep(10)
        val error = StreamingIndicator.renderError(phase, 5)

        error.shouldContain("❌")
        error.shouldContain("Generation failed")
        error.shouldContain("5 tokens")
    }
})
