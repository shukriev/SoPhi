package dev.sophi.ai.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MessageTest : FunSpec({
    test("Message preserves role and content") {
        val msg = Message(MessageRole.USER, "hello")
        msg.role shouldBe MessageRole.USER
        msg.content shouldBe "hello"
        msg.toolCallId shouldBe null
        msg.toolName shouldBe null
    }

    test("TOOL message carries toolCallId and toolName") {
        val msg = Message(MessageRole.TOOL, "result", toolCallId = "call_abc", toolName = "search")
        msg.toolCallId shouldBe "call_abc"
        msg.toolName shouldBe "search"
    }

    test("all four roles are defined") {
        MessageRole.values().map { it.name }.toSet() shouldBe
            setOf("SYSTEM", "USER", "ASSISTANT", "TOOL")
    }
})
