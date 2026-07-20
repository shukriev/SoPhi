package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class TokenStreamFormatterTest : FunSpec({
    test("formatTokenCount shows total count and elapsed time for Generating") {
        val phase = StreamingPhase.Generating(tokenCount = 3)
        val count = TokenStreamFormatter.formatTokenCount(phase)
        count.shouldContain("3")
        count.shouldContain("tokens")
    }

    test("formatTokenCount shows elapsed time for ExecutingTool") {
        val phase = StreamingPhase.ExecutingTool(toolName = "read_file")
        val count = TokenStreamFormatter.formatTokenCount(phase)
        count.shouldContain("s")
    }

    test("renderTokenStream shows the raw text with a trailing stats footer") {
        val phase = StreamingPhase.Generating(tokenCount = 2)
        val stream = TokenStreamFormatter.renderTokenStream(phase, "Hello world")
        stream.shouldContain("Hello world")
        stream.shouldContain("2 tokens")
    }

    test("renderTokenStream for ExecutingTool shows the tool name") {
        val phase = StreamingPhase.ExecutingTool(toolName = "read_file")
        val stream = TokenStreamFormatter.renderTokenStream(phase, "")
        stream.shouldContain("read_file")
    }
})
