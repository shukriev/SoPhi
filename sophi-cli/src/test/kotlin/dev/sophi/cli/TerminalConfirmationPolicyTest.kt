package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import dev.sophi.core.audit.DecisionNotes
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

    test("confirm() notes rule user for every call it answers, yes or no") {
        val requests = listOf(bashRequest, ConfirmationRequest("c2", "write_file", "{}", RiskLevel.DESTRUCTIVE))
        val notes = DecisionNotes()
        val policy = TerminalConfirmationPolicy(Terminal(), ScriptedInputSource(emptyList(), listOf(false)))

        runBlocking { withContext(notes) { policy.confirm(requests) } }

        notes.rules shouldBe mapOf("c1" to "user", "c2" to "user")
    }

    fun rulesAt(file: java.nio.file.Path, home: java.nio.file.Path) =
        dev.sophi.core.permissions.PermissionRules.load(home.resolve("g.json"), file, home.resolve("trust.json"))

    val gitStatus = dev.sophi.core.permissions.PermissionRule("bash", "git status", dev.sophi.core.permissions.Effect.ALLOW)
    val alwaysRequest = ConfirmationRequest("c1", "bash", """{"command":"git status"}""", RiskLevel.DESTRUCTIVE, alwaysAllow = listOf(gitStatus))

    test("answering a writes the shown rule and allows the call") {
        val dir = java.nio.file.Files.createTempDirectory("rules")
        val file = dir.resolve("p.json")
        val recorder = TerminalRecorder()
        val input = ScriptedInputSource(emptyList(), confirmAnswers = listOf(ConfirmAnswer.ALWAYS))
        val rules = rulesAt(file, dir)
        val policy = TerminalConfirmationPolicy(Terminal(terminalInterface = recorder), input, rules)
        runBlocking { policy.confirm(listOf(alwaysRequest)) } shouldBe mapOf("c1" to true)
        recorder.output() shouldContain "a=always"
        recorder.output() shouldContain "git status"
        rulesAt(file, dir).active shouldBe listOf(gitStatus)
        input.offers shouldBe listOf(true)
    }

    test("a is not offered when a request has no alwaysAllow") {
        val dir = java.nio.file.Files.createTempDirectory("rules")
        val recorder = TerminalRecorder()
        val input = ScriptedInputSource(emptyList(), listOf(true))
        val policy = TerminalConfirmationPolicy(Terminal(terminalInterface = recorder), input, rulesAt(dir.resolve("p.json"), dir))
        runBlocking { policy.confirm(listOf(bashRequest)) } shouldBe mapOf("c1" to true)
        recorder.output() shouldNotContain "a=always"
        input.offers shouldBe listOf(false)
    }

    test("a failed rule write warns and still allows the call") {
        val dir = java.nio.file.Files.createTempDirectory("rules")
        val file = dir.resolve("p.json")
        val rules = rulesAt(file, dir)
        java.nio.file.Files.writeString(file, "{ not json")
        val recorder = TerminalRecorder()
        val input = ScriptedInputSource(emptyList(), confirmAnswers = listOf(ConfirmAnswer.ALWAYS))
        val policy = TerminalConfirmationPolicy(Terminal(terminalInterface = recorder), input, rules)
        runBlocking { policy.confirm(listOf(alwaysRequest)) } shouldBe mapOf("c1" to true)
        recorder.output() shouldContain "Could not save the rule:"
    }
})
