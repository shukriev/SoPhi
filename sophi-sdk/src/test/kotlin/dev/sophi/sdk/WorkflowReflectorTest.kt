package dev.sophi.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking

private const val FENCE = "```"

class WorkflowReflectorTest : FunSpec({
    val turn = FinishedTurn(
        request = "archive the Trello emails in Gmail",
        toolCalls = listOf(
            ToolCallRecord("browser_open", """{"url":"https://mail.google.com"}""", "ok", false),
            ToolCallRecord("browser_fill", """{"name":"Search","value":"from:trello"}""", "x".repeat(5_000), false),
            ToolCallRecord("browser_click", """{"name":"Archive"}""", "ok", false),
        ),
        answer = "Archived 12 emails.",
    )
    fun reflect(reply: String?) = runBlocking { WorkflowReflector(ScriptedProvider(reply), "m").reflect(turn, emptyList()) }

    test("not reusable") { reflect("""{"reusable": false}""") shouldBe Reflection(reusable = false) }

    test("a generalised skill with parameters, through think blocks and fences") {
        val r = reflect("<think>hm</think>${FENCE}json\n" + """{"reusable": true, "update": null, "id": "archive-sender", "title": "Archive a sender's mail",
            "description": "Archive all Gmail mail from one sender", "params": [{"name": "sender", "description": "who"}],
            "body": "1. browser_fill Search with from:{sender}\n2. Archive"}""" + "\n$FENCE")!!
        r.reusable shouldBe true
        r.params.single().name shouldBe "sender"
        r.body shouldContain "{sender}"
    }

    test("invalid JSON or an error → null") {
        reflect("sure, I'd save that") shouldBe null
        reflect(null) shouldBe null
    }

    test("the prompt carries the turn as fenced data, truncated, plus existing auto-skills") {
        val p = WorkflowReflector(ScriptedProvider(), "m")
            .prompt(turn, listOf("auto-archive-sender" to "Archive a sender's mail: Archive all Gmail mail from one sender"))
        p shouldContain "archive the Trello emails in Gmail"
        p shouldContain "browser_fill"
        p shouldContain "auto-archive-sender"
        p shouldNotContain "x".repeat(1_000)
        p shouldContain "untrusted"
    }
})
