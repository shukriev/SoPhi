package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class TerminalConfirmationPolicyTest : FunSpec({
    test("confirm() returns true when the answer is yes") {
        val policy = TerminalConfirmationPolicy(Terminal(), ScriptedInputSource(emptyList(), listOf(true)))
        runBlocking { policy.confirm("bash", """{"command":"ls"}""") } shouldBe true
    }

    test("confirm() returns false when the answer is no") {
        val policy = TerminalConfirmationPolicy(Terminal(), ScriptedInputSource(emptyList(), listOf(false)))
        runBlocking { policy.confirm("bash", """{"command":"ls"}""") } shouldBe false
    }

    test("confirm() returns false when there is no answer queued") {
        val policy = TerminalConfirmationPolicy(Terminal(), ScriptedInputSource(emptyList()))
        runBlocking { policy.confirm("bash", """{"command":"ls"}""") } shouldBe false
    }
})
