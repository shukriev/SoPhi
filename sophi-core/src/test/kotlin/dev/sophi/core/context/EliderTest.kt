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
})
