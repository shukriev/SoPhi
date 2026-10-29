package dev.sophi.core.tools

import dev.sophi.core.session.SessionIdContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private class Recorder(private val reply: Answer = Answer.Dismissed) {
    val asked = mutableListOf<Pair<UserQuestion, Long?>>()
    val asker = UserAsker { q, t -> asked += q to t; reply }
}

private fun tool(r: Recorder) = AskUserTool(r.asker, nowMs = { 1_000L })

private fun runIn(session: String?, extra: CoroutineContext = EmptyCoroutineContext, block: suspend () -> String): String = runBlocking {
    if (session == null) block() else withContext(SessionIdContext(session) + extra) { block() }
}

class AskUserToolTest : FunSpec({
    test("is SAFE so it never asks permission to ask") {
        tool(Recorder()).riskLevel("{}") shouldBe RiskLevel.SAFE
    }

    test("outside a session it errors and asks nothing") {
        val r = Recorder()
        runIn(null) { tool(r).execute("""{"question":"Which?"}""") } shouldStartWith "Error:"
        r.asked shouldBe emptyList()
    }

    test("attended has no timeout") {
        val r = Recorder()
        runIn("s1") { tool(r).execute("""{"question":"Which approach?","options":[{"label":"Queue","description":"one list"},{"label":"Window"}]}""") }
        val (q, timeout) = r.asked.single()
        q.sessionId shouldBe "s1"
        q.text shouldBe "Which approach?"
        q.choices shouldBe listOf(Choice("Queue", "one list"), Choice("Window"))
        q.allowFreeText shouldBe true
        q.unattended shouldBe false
        q.deadlineMs shouldBe null
        timeout shouldBe null
    }

    test("unattended context times out, nested context inherits") {
        val r = Recorder(Answer.TimedOut)
        val direct = runIn("g1", Unattended) { tool(r).execute("""{"question":"Which calendar?"}""") }
        val nested = runIn("g1", Unattended) { kotlinx.coroutines.coroutineScope { async { tool(r).execute("""{"question":"Again?"}""") }.await() } }
        r.asked.size shouldBe 2
        r.asked.forEach { (q, timeout) ->
            q.unattended shouldBe true
            timeout shouldBe 240_000L
            q.deadlineMs shouldBe 241_000L
        }
        direct shouldBe "User did not answer within 4 minutes — they may be away. Proceed with your best judgment or stop."
        nested shouldBe direct
    }

    test("options given as plain strings are accepted as labels") {
        val r = Recorder()
        runIn("s1") { tool(r).execute("""{"question":"Colour?","options":["Red","Blue"]}""") }
        r.asked.single().first.choices shouldBe listOf(Choice("Red"), Choice("Blue"))
    }

    test("blank option labels are dropped") {
        val r = Recorder()
        runIn("s1") { tool(r).execute("""{"question":"Colour?","options":["Red","  ",{"label":""},{"label":"Blue"}]}""") }
        r.asked.single().first.choices shouldBe listOf(Choice("Red"), Choice("Blue"))
    }

    test("more than 4 options is an error the model can fix") {
        val r = Recorder()
        runIn("s1") { tool(r).execute("""{"question":"?","options":["a","b","c","d","e"]}""") } shouldStartWith "Error:"
        r.asked shouldBe emptyList()
    }

    test("no options and no free text is an error") {
        val r = Recorder()
        runIn("s1") { tool(r).execute("""{"question":"?","allow_free_text":false}""") } shouldStartWith "Error:"
        r.asked shouldBe emptyList()
    }

    test("malformed arguments return an error instead of throwing") {
        val r = Recorder()
        runIn("s1") { tool(r).execute("not json") } shouldStartWith "Error:"
        runIn("s1") { tool(r).execute("""{"options":["a"]}""") } shouldStartWith "Error:"
        runIn("s1") { tool(r).execute("""["a"]""") } shouldStartWith "Error:"
        r.asked shouldBe emptyList()
    }

    context("result text") {
        val q = UserQuestion("s1", "Which?", listOf(Choice("Queue"), Choice("Window")), allowFreeText = true, unattended = false, deadlineMs = null)
        test("a chosen option names its letter") {
            describeAnswer(q, Answer.Reply(Choice("Window"), null), null) shouldBe "User chose B: \"Window\""
        }
        test("an option with a note carries both") {
            describeAnswer(q, Answer.Reply(Choice("Queue"), " keep it small "), null) shouldBe "User chose A: \"Queue\". Note: keep it small"
        }
        test("free text alone") {
            describeAnswer(q, Answer.Reply(null, "neither"), null) shouldBe "User replied: neither"
        }
        test("whitespace-only text with no option reads as dismissed") {
            describeAnswer(q, Answer.Reply(null, "   "), null) shouldStartWith "User dismissed"
        }
        test("dismissed") {
            describeAnswer(q, Answer.Dismissed, null) shouldBe "User dismissed the question — proceed with your best judgment or stop."
        }
    }
})
