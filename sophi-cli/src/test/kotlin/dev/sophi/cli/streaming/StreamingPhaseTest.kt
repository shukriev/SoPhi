package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StreamingPhaseTest : FunSpec({
    test("Generating phase tracks token count") {
        val phase = StreamingPhase.Generating(tokenCount = 2)
        phase.tokenCount shouldBe 2
    }

    test("ExecutingTool phase stores tool name") {
        val phase = StreamingPhase.ExecutingTool(toolName = "read_file")
        phase.toolName shouldBe "read_file"
    }

    test("elapsedSeconds is non-negative") {
        val phase = StreamingPhase.Generating()
        (phase.elapsedSeconds() >= 0.0) shouldBe true
    }
})
