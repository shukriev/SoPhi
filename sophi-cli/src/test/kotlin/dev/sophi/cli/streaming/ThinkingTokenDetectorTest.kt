package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ThinkingTokenDetectorTest : FunSpec({
    test("detects thinking block start") {
        ThinkingTokenDetector.reset()
        val (isThinking, _) = ThinkingTokenDetector.parseThinkingBlock("<thinking>")
        isThinking shouldBe true
    }

    test("detects thinking block end") {
        ThinkingTokenDetector.reset()
        ThinkingTokenDetector.parseThinkingBlock("<thinking>")
        val (isThinking, _) = ThinkingTokenDetector.parseThinkingBlock("</thinking>")
        isThinking shouldBe false
    }

    test("normal tokens are not thinking") {
        ThinkingTokenDetector.reset()
        val (isThinking, text) = ThinkingTokenDetector.parseThinkingBlock("hello")
        isThinking shouldBe false
        text shouldBe "hello"
    }

    test("removes thinking tags from token text") {
        ThinkingTokenDetector.reset()
        val (_, cleanToken) = ThinkingTokenDetector.parseThinkingBlock("<thinking>some text")
        cleanToken shouldBe "some text"
    }
})
