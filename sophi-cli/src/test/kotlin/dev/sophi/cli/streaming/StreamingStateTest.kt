package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StreamingStateTest : FunSpec({
    test("can create all state variants") {
        val states = listOf(
            StreamingState.IDLE,
            StreamingState.GENERATING,
            StreamingState.EXECUTING_TOOL,
            StreamingState.COMPLETE
        )
        states.size shouldBe 4
    }
})

class StreamingPhaseTest : FunSpec({
    test("Generating phase tracks token counts") {
        val phase = StreamingPhase.Generating(responseTokens = listOf("a", "b"))
        phase.tokenCount shouldBe 2
        phase.thinkingTokens.isEmpty() shouldBe true
    }

    test("Generating phase combines thinking and response tokens") {
        val phase = StreamingPhase.Generating(
            thinkingTokens = listOf("t1", "t2"),
            responseTokens = listOf("r1", "r2", "r3")
        )
        phase.tokenCount shouldBe 5
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
