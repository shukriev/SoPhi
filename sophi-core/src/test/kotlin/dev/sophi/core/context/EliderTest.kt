package dev.sophi.core.context

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.ToolCall
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EliderTest : FunSpec({
    // Stubs only replace results longer than the stub, so test data must be bulky.
    val big = List(10) { "some output line number $it" }.joinToString("\n")

    fun round(id: String, name: String, args: String, result: String) = listOf(
        Message(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall(id, name, args))),
        Message(MessageRole.TOOL, result, toolCallId = id, toolName = name))

    test("rounds older than the last K are stubbed with tool, key argument and line count; pairing kept") {
        val m = (listOf(Message(MessageRole.USER, "q")) +
            round("1", "read_file", """{"path":"a/B.kt"}""", big) +
            round("2", "bash", """{"command":"mvn -q test"}""", big) +
            round("3", "grep", """{"pattern":"TODO"}""", "x")).toMutableList()
        Elider.elide(m, from = 1, keepRecentRounds = 1) shouldBe 2
        m[2].content shouldBe "[elided: read_file a/B.kt, 10 lines — re-read if needed]"
        m[4].content shouldBe "[elided: bash mvn -q test, 10 lines — re-read if needed]"
        m[6].content shouldBe "x"
        m.map { it.role } shouldBe listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT, MessageRole.TOOL)
        m[2].toolCallId shouldBe "1"
    }

    test("already-elided results are not counted again; nothing older than K means 0") {
        val m = (round("1", "read_file", """{"path":"a"}""", big) + round("2", "read_file", """{"path":"b"}""", big)).toMutableList()
        Elider.elide(m, 0, 1) shouldBe 1
        Elider.elide(m, 0, 1) shouldBe 0
        Elider.elide(m, 0, 5) shouldBe 0
    }

    test("a result shorter than its stub is left alone") {
        val m = (round("1", "read_file", """{"path":"a"}""", "ok") + round("2", "read_file", """{"path":"b"}""", "y")).toMutableList()
        Elider.elide(m, 0, 1) shouldBe 0
        m[1].content shouldBe "ok"
    }

    test("unparseable arguments and non-file tools are stubbed with the tool name only") {
        val m = (round("1", "web_search", "not json", big) + round("2", "read_file", """{"path":"x"}""", "y")).toMutableList()
        Elider.elide(m, 0, 1) shouldBe 1
        m[1].content shouldBe "[elided: web_search, 10 lines — re-read if needed]"
    }

    test("keepRecentRounds 0 (or negative) elides every round") {
        val m = (round("1", "read_file", """{"path":"a"}""", big) + round("2", "read_file", """{"path":"b"}""", big)).toMutableList()
        Elider.elide(m, 0, 0) shouldBe 2
        val n = round("1", "read_file", """{"path":"a"}""", big).toMutableList()
        Elider.elide(n, 0, -1) shouldBe 1
    }

    test("parallel calls in one round each get their own key argument") {
        val m = mutableListOf(
            Message(MessageRole.ASSISTANT, "", toolCalls = listOf(
                ToolCall("1", "read_file", """{"path":"a"}"""), ToolCall("2", "read_file", """{"path":"b"}"""))),
            Message(MessageRole.TOOL, big, toolCallId = "1", toolName = "read_file"),
            Message(MessageRole.TOOL, big, toolCallId = "2", toolName = "read_file"))
        m.addAll(round("3", "grep", """{"pattern":"p"}""", "z"))
        Elider.elide(m, 0, 1) shouldBe 2
        m[1].content shouldBe "[elided: read_file a, 10 lines — re-read if needed]"
        m[2].content shouldBe "[elided: read_file b, 10 lines — re-read if needed]"
    }

    test("a result whose call id matches nothing is stubbed with its tool name") {
        val m = (listOf(Message(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("1", "bash", "{}"))),
            Message(MessageRole.TOOL, big, toolCallId = "orphan", toolName = "bash")) +
            round("2", "grep", """{"pattern":"p"}""", "z")).toMutableList()
        Elider.elide(m, 0, 1) shouldBe 1
        m[1].content shouldBe "[elided: bash, 10 lines — re-read if needed]"
    }

    test("elideLargest stubs the biggest results first, the newest round included, until enough is saved") {
        fun msgs() = (listOf(Message(MessageRole.USER, "q"),
            Message(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("a", "read_file", """{"path":"a"}"""), ToolCall("b", "read_file", """{"path":"b"}"""), ToolCall("c", "read_file", """{"path":"c"}"""))),
            Message(MessageRole.TOOL, "x".repeat(5000), toolCallId = "a", toolName = "read_file"),
            Message(MessageRole.TOOL, "x".repeat(200), toolCallId = "b", toolName = "read_file"),
            Message(MessageRole.TOOL, "x".repeat(3000), toolCallId = "c", toolName = "read_file"))).toMutableList()
        val m = msgs()
        val saved = Elider.elideLargest(m, 1, 4000)
        m[2].content.startsWith(Elider.PREFIX) shouldBe true
        m[3].content.length shouldBe 200
        m[4].content.length shouldBe 3000
        saved shouldBe 5000 - m[2].content.length
        val m2 = msgs()
        val saved2 = Elider.elideLargest(m2, 1, 7000)
        m2[2].content.startsWith(Elider.PREFIX) shouldBe true
        m2[4].content.startsWith(Elider.PREFIX) shouldBe true
        m2[3].content.length shouldBe 200
        saved2 shouldBe 8000 - m2[2].content.length - m2[4].content.length
        // already-stubbed messages are skipped
        val stubA = m2[2].content
        val stubC = m2[4].content
        val before = m2[3].content.length
        val rest = Elider.elideLargest(m2, 1, 1_000_000)  // only the 200-char one is left to stub
        m2[2].content shouldBe stubA
        m2[4].content shouldBe stubC
        rest shouldBe before - m2[3].content.length
        m2[3].content.startsWith(Elider.PREFIX) shouldBe true
        m2[2].toolCallId shouldBe "a"
    }

    test("elideLargest prefers file tools first, and the newest round gets the too-large stub") {
        val m = (listOf(Message(MessageRole.USER, "q")) +
            round("1", "bash", """{"command":"ls"}""", "x".repeat(9000)) +
            round("2", "read_file", """{"path":"a"}""", "y".repeat(2000))).toMutableList()
        Elider.elideLargest(m, 1, 1)
        m[4].content shouldBe "[elided: read_file a, 1 lines — too large to show at once; re-read a smaller part or narrow the request]"
        m[2].content.length shouldBe 9000
    }
})
