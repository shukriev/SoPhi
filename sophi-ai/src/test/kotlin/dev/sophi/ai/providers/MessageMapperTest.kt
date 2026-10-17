package dev.sophi.ai.providers

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.ToolCall
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.ai.chat.messages.SystemMessage

// Compacted message shapes carry no SYSTEM role, so toSpring() (Claude path) yields no SystemMessage.
// OpenAICompat streaming maps through toRawMessageParam, not toSpring(), so it is not covered here.
class MessageMapperTest : FunSpec({
    fun check(input: List<Message>) {
        val out = input.map { it.toSpring() }
        out.none { it is SystemMessage } shouldBe true
        out.map { it.messageType } shouldBe input.map {
            when (it.role) {
                MessageRole.USER -> org.springframework.ai.chat.messages.MessageType.USER
                MessageRole.ASSISTANT -> org.springframework.ai.chat.messages.MessageType.ASSISTANT
                MessageRole.TOOL -> org.springframework.ai.chat.messages.MessageType.TOOL
                MessageRole.SYSTEM -> org.springframework.ai.chat.messages.MessageType.SYSTEM
            }
        }
    }

    test("a compacted mid-turn list maps without any system message") {
        check(listOf(
            Message(MessageRole.USER, "go\n\n[Earlier steps this turn, summarised]\nsummary"),
            Message(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("c1", "ok", "{}"))),
            Message(MessageRole.TOOL, "fine", toolCallId = "c1", toolName = "ok")
        ))
    }

    test("a compacted cross-turn list maps without any system message") {
        check(listOf(
            Message(MessageRole.USER, "Summary of the conversation so far:\ns"),
            Message(MessageRole.ASSISTANT, "Understood — continuing from that summary."),
            Message(MessageRole.USER, "next"),
            Message(MessageRole.ASSISTANT, "ok")
        ))
    }
})
