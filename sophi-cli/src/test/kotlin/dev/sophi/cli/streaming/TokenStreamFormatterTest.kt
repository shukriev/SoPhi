package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class TokenStreamFormatterTest : FunSpec({
    test("formatToken for thinking block adds thinking label") {
        val formatted = TokenStreamFormatter.formatToken("The user asks...", isThinking = true)
        formatted.shouldContain("[thinking]")
    }

    test("formatToken for response adds response label") {
        val formatted = TokenStreamFormatter.formatToken("I can help...", isThinking = false)
        formatted.shouldContain("[response]")
    }

    test("formatTokenCount shows total count for Generating") {
        val phase = StreamingPhase.Generating(
            thinkingTokens = listOf("a", "b"),
            responseTokens = listOf("c")
        )
        val count = TokenStreamFormatter.formatTokenCount(phase)
        count.shouldContain("3")
        count.shouldContain("tokens")
    }

    test("renderTokenStream separates thinking and response blocks") {
        val phase = StreamingPhase.Generating(
            thinkingTokens = listOf("think"),
            responseTokens = listOf("resp")
        )
        val stream = TokenStreamFormatter.renderTokenStream(phase)
        stream.shouldContain("[thinking]")
        stream.shouldContain("[response]")
    }
})
