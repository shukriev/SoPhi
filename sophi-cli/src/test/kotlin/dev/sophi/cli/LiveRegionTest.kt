package dev.sophi.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LiveRegionTest : FunSpec({
    test("update() on first call writes the text with no cursor-reposition codes") {
        val out = StringBuilder()
        val region = LiveRegion(out) { 80 }

        region.update("Hello")

        out.toString() shouldBe "Hello"
    }

    test("update() called again on a single-line region repositions with carriage-return and erase only") {
        val out = StringBuilder()
        val region = LiveRegion(out) { 80 }
        region.update("Hello")
        out.clear()

        region.update("Hi")

        out.toString() shouldBe "\r[0JHi"
    }

    test("update() wraps text to the given width and tracks the wrapped line count") {
        val out = StringBuilder()
        val region = LiveRegion(out) { 5 }
        region.update("HelloWorld")
        out.clear()

        region.update("Hi")

        // previous draw was 2 lines ("Hello" / "World") -> move up 1, then erase
        out.toString() shouldBe "\r[1A[0JHi"
    }

    test("update() with empty text draws one empty line") {
        val out = StringBuilder()
        val region = LiveRegion(out) { 80 }

        region.update("")

        out.toString() shouldBe ""
        out.clear()
        region.update("X")
        out.toString() shouldBe "\r[0JX"
    }

    test("clear() erases previously drawn content and leaves nothing behind") {
        val out = StringBuilder()
        val region = LiveRegion(out) { 80 }
        region.update("Hello")
        out.clear()

        region.clear()

        out.toString() shouldBe "\r[0J"
    }

    test("clear() on an already-empty region writes nothing") {
        val out = StringBuilder()
        val region = LiveRegion(out) { 80 }

        region.clear()

        out.toString() shouldBe ""
    }

    test("update() after clear() writes fresh with no reposition codes") {
        val out = StringBuilder()
        val region = LiveRegion(out) { 80 }
        region.update("Hello")
        region.clear()
        out.clear()

        region.update("Next")

        out.toString() shouldBe "Next"
    }
})
