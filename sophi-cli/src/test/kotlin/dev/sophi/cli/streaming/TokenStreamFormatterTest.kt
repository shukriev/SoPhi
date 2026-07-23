package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class TokenStreamFormatterTest : FunSpec({
    test("formatTokenCount shows total count and elapsed time for Generating") {
        val phase = StreamingPhase.Generating(tokenCount = 3)
        val count = TokenStreamFormatter.formatTokenCount(phase)
        count.shouldContain("3")
        count.shouldContain("tokens")
    }

    test("formatTokenCount sums content and reasoning token counts") {
        val phase = StreamingPhase.Generating(tokenCount = 3, reasoningTokenCount = 5)
        TokenStreamFormatter.formatTokenCount(phase) shouldContain "8 tokens"
    }

    test("formatTokenCount shows elapsed time for ExecutingTool") {
        val phase = StreamingPhase.ExecutingTool(toolName = "read_file")
        val count = TokenStreamFormatter.formatTokenCount(phase)
        count.shouldContain("s")
    }

    test("renderTokenStream shows the raw content text with a trailing stats footer") {
        val phase = StreamingPhase.Generating(tokenCount = 2)
        val stream = TokenStreamFormatter.renderTokenStream(phase, reasoningText = "", contentText = "Hello world")
        stream.shouldContain("Hello world")
        stream.shouldContain("2 tokens")
    }

    test("renderTokenStream shows the reasoning buffer ahead of the content buffer") {
        val phase = StreamingPhase.Generating(tokenCount = 1, reasoningTokenCount = 1)
        val rendered = TokenStreamFormatter.renderTokenStream(phase, reasoningText = "thinking...", contentText = "the answer")
        val reasoningIndex = rendered.indexOf("thinking...")
        val contentIndex = rendered.indexOf("the answer")
        (reasoningIndex in 0 until contentIndex) shouldBe true
    }

    test("renderTokenStream renders only content when there is no reasoning text") {
        val phase = StreamingPhase.Generating(tokenCount = 1, reasoningTokenCount = 0)
        val rendered = TokenStreamFormatter.renderTokenStream(phase, reasoningText = "", contentText = "the answer")
        rendered shouldContain "the answer"
    }

    test("renderTokenStream for ExecutingTool shows the tool name") {
        val phase = StreamingPhase.ExecutingTool(toolName = "read_file")
        val stream = TokenStreamFormatter.renderTokenStream(phase, reasoningText = "", contentText = "")
        stream.shouldContain("read_file")
    }
})
