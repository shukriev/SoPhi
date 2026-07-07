package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class TerminalConfirmationPolicyTest : FunSpec({
    test("confirm() returns true when the answer is y") {
        val policy = TerminalConfirmationPolicy(Terminal()) { "y" }
        runBlocking { policy.confirm("bash", """{"command":"ls"}""") } shouldBe true
    }

    test("confirm() returns true when the answer is Y (case-insensitive)") {
        val policy = TerminalConfirmationPolicy(Terminal()) { "Y" }
        runBlocking { policy.confirm("bash", """{"command":"ls"}""") } shouldBe true
    }

    test("confirm() returns false when the answer is n") {
        val policy = TerminalConfirmationPolicy(Terminal()) { "n" }
        runBlocking { policy.confirm("bash", """{"command":"ls"}""") } shouldBe false
    }

    test("confirm() returns false when there is no input") {
        val policy = TerminalConfirmationPolicy(Terminal()) { null }
        runBlocking { policy.confirm("bash", """{"command":"ls"}""") } shouldBe false
    }
})
