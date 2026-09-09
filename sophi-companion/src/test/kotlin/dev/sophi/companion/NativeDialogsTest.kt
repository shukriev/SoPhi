package dev.sophi.companion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class NativeDialogsTest : FunSpec({
    test("on macOS, returns the typed text when OK is clicked") {
        val text = NativeDialogs.promptText(
            "work-log", "What have you worked on?", os = "Mac OS X",
            runCommand = { NativeDialogs.DialogResult(0, "button returned:OK, text returned:fixed the flaky test\n") }
        )

        text shouldBe "fixed the flaky test"
    }

    test("returns null when Cancel is clicked (nonzero exit code)") {
        val text = NativeDialogs.promptText(
            "work-log", "What have you worked on?", os = "Mac OS X",
            runCommand = { NativeDialogs.DialogResult(1, "") }
        )

        text shouldBe null
    }

    test("returns null when the typed text is blank") {
        val text = NativeDialogs.promptText(
            "work-log", "What have you worked on?", os = "Mac OS X",
            runCommand = { NativeDialogs.DialogResult(0, "button returned:OK, text returned:   \n") }
        )

        text shouldBe null
    }

    test("on a non-macOS platform, returns null without invoking runCommand") {
        var called = false

        val text = NativeDialogs.promptText(
            "work-log", "What have you worked on?", os = "Linux",
            runCommand = { called = true; NativeDialogs.DialogResult(0, "") }
        )

        text shouldBe null
        called shouldBe false
    }

    test("swallows an exception thrown by runCommand rather than propagating") {
        val text = NativeDialogs.promptText(
            "work-log", "What have you worked on?", os = "Mac OS X",
            runCommand = { throw java.io.IOException("osascript missing") }
        )

        text shouldBe null
    }

    test("passes title into the osascript command and escapes double quotes in the message") {
        var captured: List<String>? = null

        NativeDialogs.promptText(
            "work-log", """found "urgent" thing""", os = "Mac OS X",
            runCommand = { cmd -> captured = cmd; NativeDialogs.DialogResult(0, "button returned:OK, text returned:x") }
        )

        captured!![0] shouldBe "osascript"
        captured!![1] shouldBe "-e"
        captured!![2] shouldContain "work-log"
        captured!![2] shouldContain """\"urgent\""""
    }
})
