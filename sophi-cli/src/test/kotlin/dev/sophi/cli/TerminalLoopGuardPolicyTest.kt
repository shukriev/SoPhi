package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class TerminalLoopGuardPolicyTest : FunSpec({
    test("askToContinue() returns true when the answer is yes") {
        val policy = TerminalLoopGuardPolicy(Terminal(), ScriptedInputSource(emptyList(), listOf(true)))
        runBlocking { policy.askToContinue("3 consecutive tool failures") } shouldBe true
    }

    test("askToContinue() returns false when the answer is no") {
        val policy = TerminalLoopGuardPolicy(Terminal(), ScriptedInputSource(emptyList(), listOf(false)))
        runBlocking { policy.askToContinue("3 consecutive tool failures") } shouldBe false
    }

    test("askToContinue() returns false when there is no answer queued") {
        val policy = TerminalLoopGuardPolicy(Terminal(), ScriptedInputSource(emptyList()))
        runBlocking { policy.askToContinue("3 consecutive tool failures") } shouldBe false
    }
})
