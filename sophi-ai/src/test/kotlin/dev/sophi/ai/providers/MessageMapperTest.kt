package dev.sophi.ai.providers

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.ToolCall
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import dev.sophi.ai.api.ImageRef
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes

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

    fun png(): String = createTempFile(suffix = ".png").also { it.writeBytes(byteArrayOf(-119, 80, 78, 71)) }.toString()

    test("user message with an image maps to UserMessage media") {
        val m = Message(MessageRole.USER, "look", images = listOf(ImageRef(png(), "image/png")))
        val u = m.toSpring() as UserMessage
        u.media.size shouldBe 1
        u.media.single().mimeType.toString() shouldBe "image/png"
    }

    test("missing image is left out with a note") {
        val u = Message(MessageRole.USER, "look", images = listOf(ImageRef("/nope/gone.png", "image/png"))).toSpring() as UserMessage
        u.media.size shouldBe 0
        u.text shouldEndWith "[image left out: file missing]"
    }

    test("five images keep four") {
        val u = Message(MessageRole.USER, "look", images = List(5) { ImageRef(png(), "image/png") }).toSpring() as UserMessage
        u.media.size shouldBe 4
        u.text shouldEndWith "[image left out: more than 4 images]"
    }
})
