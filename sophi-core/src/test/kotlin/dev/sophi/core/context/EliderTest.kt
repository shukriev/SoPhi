package dev.sophi.core.context

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.ToolCall
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EliderTest : FunSpec({
    fun round(id: String, name: String, args: String, result: String) = listOf(
        Message(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall(id, name, args))),
        Message(MessageRole.TOOL, result, toolCallId = id, toolName = name))

    test("rounds older than the last K are stubbed with tool, key argument and line count; pairing kept") {
        val m = (listOf(Message(MessageRole.USER, "q")) +
            round("1", "read_file", """{"path":"a/B.kt"}""", "l1\nl2\nl3") +
            round("2", "bash", """{"command":"mvn -q test"}""", "ok") +
            round("3", "grep", """{"pattern":"TODO"}""", "x")).toMutableList()
        Elider.elide(m, from = 1, keepRecentRounds = 1) shouldBe 2
        m[2].content shouldBe "[elided: read_file a/B.kt, 3 lines — re-read if needed]"
        m[4].content shouldBe "[elided: bash mvn -q test, 1 lines — re-read if needed]"
        m[6].content shouldBe "x"
        m.map { it.role } shouldBe listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT, MessageRole.TOOL)
        m[2].toolCallId shouldBe "1"
    }

    test("already-elided results are not counted again; nothing older than K means 0") {
        val m = (round("1", "read_file", """{"path":"a"}""", "z") + round("2", "read_file", """{"path":"b"}""", "y")).toMutableList()
        Elider.elide(m, 0, 1) shouldBe 1
        Elider.elide(m, 0, 1) shouldBe 0
        Elider.elide(m, 0, 5) shouldBe 0
    }

    test("unparseable arguments and non-file tools are stubbed with the tool name only") {
        val m = (round("1", "web_search", "not json", "a\nb") + round("2", "read_file", """{"path":"x"}""", "y")).toMutableList()
        Elider.elide(m, 0, 1) shouldBe 1
        m[1].content shouldBe "[elided: web_search, 2 lines — re-read if needed]"
    }

    test("keepRecentRounds 0 (or negative) elides every round") {
        val m = (round("1", "read_file", """{"path":"a"}""", "z") + round("2", "read_file", """{"path":"b"}""", "y")).toMutableList()
        Elider.elide(m, 0, 0) shouldBe 2
        val n = round("1", "read_file", """{"path":"a"}""", "z").toMutableList()
        Elider.elide(n, 0, -1) shouldBe 1
    }

    test("parallel calls in one round each get their own key argument") {
        val m = mutableListOf(
            Message(MessageRole.ASSISTANT, "", toolCalls = listOf(
                ToolCall("1", "read_file", """{"path":"a"}"""), ToolCall("2", "read_file", """{"path":"b"}"""))),
            Message(MessageRole.TOOL, "x", toolCallId = "1", toolName = "read_file"),
            Message(MessageRole.TOOL, "y", toolCallId = "2", toolName = "read_file"))
        m.addAll(round("3", "grep", """{"pattern":"p"}""", "z"))
        Elider.elide(m, 0, 1) shouldBe 2
        m[1].content shouldBe "[elided: read_file a, 1 lines — re-read if needed]"
        m[2].content shouldBe "[elided: read_file b, 1 lines — re-read if needed]"
    }

    test("a result whose call id matches nothing is stubbed with its tool name") {
        val m = (listOf(Message(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("1", "bash", "{}"))),
            Message(MessageRole.TOOL, "q", toolCallId = "orphan", toolName = "bash")) +
            round("2", "grep", """{"pattern":"p"}""", "z")).toMutableList()
        Elider.elide(m, 0, 1) shouldBe 1
        m[1].content shouldBe "[elided: bash, 1 lines — re-read if needed]"
    }
})
