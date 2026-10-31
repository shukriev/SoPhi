package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import dev.sophi.core.tools.Answer
import dev.sophi.core.tools.Choice
import dev.sophi.core.tools.UserQuestion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

private val CHOICES = listOf(Choice("Postgres", "one server"), Choice("SQLite"))
private fun q(text: String = "Which database?", free: Boolean = true) =
    UserQuestion("s", text, CHOICES, allowFreeText = free, unattended = false, deadlineMs = null)

class TerminalUserAskerTest : FunSpec({
    fun asker(vararg answers: String?, delayMs: Long = 0): Pair<TerminalUserAsker, TerminalRecorder> {
        val rec = TerminalRecorder()
        return TerminalUserAsker(Terminal(terminalInterface = rec), ScriptedInputSource(emptyList(), answers = answers.toList(), answerDelayMs = delayMs)) to rec
    }

    test("a number picks that option") {
        val (a, rec) = asker("2")
        runBlocking { a.ask(q(), null) } shouldBe Answer.Reply(CHOICES[1], null)
        rec.output() shouldContain "Sophi asks:"
        rec.output() shouldContain "1. Postgres — one server"
        rec.output() shouldContain "2. SQLite"
    }

    test("text is a free reply") {
        runBlocking { asker("use postgres").first.ask(q(), null) } shouldBe Answer.Reply(null, "use postgres")
    }

    test("Esc dismisses") {
        runBlocking { asker(null).first.ask(q(), null) } shouldBe Answer.Dismissed
    }

    test("text when free text is off asks again once") {
        runBlocking { asker("x", "1").first.ask(q(free = false), null) } shouldBe Answer.Reply(CHOICES[0], null)
    }

    test("a timeout with nobody answering is TimedOut") {
        runBlocking { asker().first.ask(q(), 50) } shouldBe Answer.TimedOut
    }

    test("two questions in parallel are asked one after the other") {
        val (a, rec) = asker("1", "2", delayMs = 50)
        val answers = runBlocking { listOf(async { a.ask(q("First?"), null) }, async { a.ask(q("Second?"), null) }).awaitAll() }
        answers.toSet() shouldBe setOf(Answer.Reply(CHOICES[0], null), Answer.Reply(CHOICES[1], null))
        val out = rec.output()
        val first = out.indexOf("Sophi asks:")
        val second = out.indexOf("Sophi asks:", first + 1)
        val promptAfterFirst = out.indexOf("Answer with a number", first)
        (promptAfterFirst in first until second) shouldBe true
    }

    test("an empty reply to a question without options asks again, without a 1-0 range") {
        val (a, rec) = asker("", "fine")
        val open = UserQuestion("s", "Anything else?", emptyList(), allowFreeText = true, unattended = false, deadlineMs = null)
        runBlocking { a.ask(open, null) } shouldBe Answer.Reply(null, "fine")
        rec.output() shouldNotContain "1-0"
    }

    test("the timeout covers waiting for an earlier question") {
        val (a, _) = asker("1", delayMs = 2_000)
        val answers = runBlocking {
            val first = async { a.ask(q("First?"), null) }
            kotlinx.coroutines.delay(50)
            val t0 = System.currentTimeMillis()
            val second = async { a.ask(q("Second?"), 200).also { (System.currentTimeMillis() - t0 < 1_000) shouldBe true } }
            listOf(first, second).awaitAll()
        }
        answers[1] shouldBe Answer.TimedOut
    }
})
