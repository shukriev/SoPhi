package dev.sophi.companion.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SentenceSplitterTest : FunSpec({
    test("a token with no sentence-ending punctuation produces no completed sentences") {
        val splitter = SentenceSplitter()

        splitter.onToken("Hello") shouldBe emptyList()
    }

    test("punctuation with no following whitespace yet stays buffered") {
        val splitter = SentenceSplitter()

        splitter.onToken("Hello world.") shouldBe emptyList()
    }

    test("punctuation followed by whitespace in a later token completes the sentence") {
        val splitter = SentenceSplitter()

        splitter.onToken("Hello world.")
        splitter.onToken(" Bye.") shouldBe listOf("Hello world.")
    }

    test("a single token containing multiple complete sentences yields all of them in order") {
        val splitter = SentenceSplitter()

        splitter.onToken("Hi. Bye. Later") shouldBe listOf("Hi.", "Bye.")
    }

    test("newline is also a sentence boundary when followed by whitespace") {
        val splitter = SentenceSplitter()

        splitter.onToken("Line one\n\nLine two") shouldBe listOf("Line one")
    }

    test("flush returns the trimmed remainder and clears the buffer") {
        val splitter = SentenceSplitter()
        splitter.onToken("Hi. Bye. Later")

        splitter.flush() shouldBe "Later"
        splitter.flush() shouldBe null
    }

    test("flush on an empty buffer returns null") {
        val splitter = SentenceSplitter()

        splitter.flush() shouldBe null
    }

    test("flush returns trailing punctuation with no following whitespace intact") {
        val splitter = SentenceSplitter()
        splitter.onToken("Done.")

        splitter.flush() shouldBe "Done."
    }
})
