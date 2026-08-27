package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking

class TerminalConfirmationPolicyTest : FunSpec({
    val bashRequest = ConfirmationRequest("c1", "bash", """{"command":"ls"}""", RiskLevel.DESTRUCTIVE)

    test("confirm() returns true for the single request when the answer is yes") {
        val policy = TerminalConfirmationPolicy(Terminal(), ScriptedInputSource(emptyList(), listOf(true)))
        runBlocking { policy.confirm(listOf(bashRequest)) } shouldBe mapOf("c1" to true)
    }

    test("confirm() returns false for the single request when the answer is no") {
        val policy = TerminalConfirmationPolicy(Terminal(), ScriptedInputSource(emptyList(), listOf(false)))
        runBlocking { policy.confirm(listOf(bashRequest)) } shouldBe mapOf("c1" to false)
    }

    test("confirm() returns false when there is no answer queued") {
        val policy = TerminalConfirmationPolicy(Terminal(), ScriptedInputSource(emptyList()))
        runBlocking { policy.confirm(listOf(bashRequest)) } shouldBe mapOf("c1" to false)
    }

    test("confirm() returns an empty map for an empty request list without prompting") {
        val policy = TerminalConfirmationPolicy(Terminal(), ScriptedInputSource(emptyList()))
        runBlocking { policy.confirm(emptyList()) } shouldBe emptyMap()
    }

    test("confirm() applies one allow-all answer to every request in a multi-call batch") {
        val requests = listOf(
            bashRequest,
            ConfirmationRequest("c2", "write_file", "{}", RiskLevel.DESTRUCTIVE)
        )
        val policy = TerminalConfirmationPolicy(Terminal(), ScriptedInputSource(emptyList(), listOf(true)))
        runBlocking { policy.confirm(requests) } shouldBe mapOf("c1" to true, "c2" to true)
    }

    test("confirm() applies one deny-all answer to every request in a multi-call batch") {
        val requests = listOf(
            bashRequest,
            ConfirmationRequest("c2", "write_file", "{}", RiskLevel.DESTRUCTIVE)
        )
        val policy = TerminalConfirmationPolicy(Terminal(), ScriptedInputSource(emptyList(), listOf(false)))
        runBlocking { policy.confirm(requests) } shouldBe mapOf("c1" to false, "c2" to false)
    }

    test("confirm() shows a tool's preview instead of raw argumentsJson when one is provided") {
        val recorder = TerminalRecorder()
        val policy = TerminalConfirmationPolicy(Terminal(terminalInterface = recorder), ScriptedInputSource(emptyList(), listOf(false)))
        val request = ConfirmationRequest("c1", "write_skill", """{"id":"site-x","body":"huge blob"}""", RiskLevel.DESTRUCTIVE, preview = "Write skill 'site-x' (new skill)")

        runBlocking { policy.confirm(listOf(request)) }

        recorder.output() shouldContain "Write skill 'site-x' (new skill)"
        recorder.output() shouldNotContain "huge blob"
    }
})
