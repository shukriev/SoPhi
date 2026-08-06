package dev.sophi.schedule.notify

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class NativeNotificationsTest : FunSpec({
    test("on macOS, shells out to osascript with title and body") {
        var captured: List<String>? = null
        NativeNotifications.send("Sophi", "task done", os = "Mac OS X", runCommand = { cmd -> captured = cmd; 0 })

        captured.shouldNotBeNull()
        captured!![0] shouldBe "osascript"
        captured!![1] shouldBe "-e"
        captured!![2] shouldContain "Sophi"
        captured!![2] shouldContain "task done"
    }

    test("on macOS, escapes double quotes in the body") {
        var captured: List<String>? = null
        NativeNotifications.send("Sophi", """found "urgent" thing""", os = "Mac OS X", runCommand = { cmd -> captured = cmd; 0 })

        captured!![2] shouldContain """\"urgent\""""
    }

    test("on Linux, shells out to notify-send with title and body as separate args") {
        var captured: List<String>? = null
        NativeNotifications.send("Sophi", "task done", os = "Linux", runCommand = { cmd -> captured = cmd; 0 })

        captured shouldBe listOf("notify-send", "Sophi", "task done")
    }

    test("on Windows, calls windowsNotify instead of shelling out") {
        var capturedTitle: String? = null
        var capturedBody: String? = null
        var runCommandCalls = 0
        NativeNotifications.send(
            "Sophi", "task done", os = "Windows 11",
            runCommand = { runCommandCalls++; 0 },
            windowsNotify = { t, b -> capturedTitle = t; capturedBody = b }
        )

        capturedTitle shouldBe "Sophi"
        capturedBody shouldBe "task done"
        runCommandCalls shouldBe 0
    }

    test("on an unrecognized OS, does nothing and does not throw") {
        var runCommandCalls = 0
        NativeNotifications.send("Sophi", "task done", os = "FreeBSD", runCommand = { runCommandCalls++; 0 })
        runCommandCalls shouldBe 0
    }

    test("swallows an exception thrown by runCommand rather than propagating") {
        NativeNotifications.send("Sophi", "task done", os = "Mac OS X", runCommand = { throw java.io.IOException("osascript missing") })
        // no assertion needed beyond "did not throw"
    }
})
