package dev.sophi.core.prompt

import dev.sophi.ai.api.MessageRole
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class PromptBuilderTest : FunSpec({

    fun entry(role: EntryRole, content: String, metadata: Map<String, String> = emptyMap()) =
        SessionEntry("id-${role.name}", null, role, content, 0L, metadata)

    test("empty entry list produces empty message list") {
        PromptBuilder.build(emptyList()).shouldBeEmpty()
    }

    test("SYSTEM entry maps to MessageRole.SYSTEM") {
        val msgs = PromptBuilder.build(listOf(entry(EntryRole.SYSTEM, "You are helpful.")))
        msgs shouldHaveSize 1
        msgs[0].role shouldBe MessageRole.SYSTEM
        msgs[0].content shouldBe "You are helpful."
    }

    test("USER entry maps to MessageRole.USER") {
        val msgs = PromptBuilder.build(listOf(entry(EntryRole.USER, "Hello!")))
        msgs[0].role shouldBe MessageRole.USER
        msgs[0].content shouldBe "Hello!"
    }

    test("ASSISTANT entry maps to MessageRole.ASSISTANT with no toolCalls") {
        val msgs = PromptBuilder.build(listOf(entry(EntryRole.ASSISTANT, "Hi there!")))
        msgs[0].role shouldBe MessageRole.ASSISTANT
        msgs[0].content shouldBe "Hi there!"
        msgs[0].toolCalls shouldBe null
    }

    test("TOOL_RESULT entry maps to MessageRole.TOOL with toolCallId and toolName from metadata") {
        val meta = mapOf("toolCallId" to "call-1", "toolName" to "search")
        val msgs = PromptBuilder.build(listOf(entry(EntryRole.TOOL_RESULT, "result text", meta)))
        msgs[0].role shouldBe MessageRole.TOOL
        msgs[0].content shouldBe "result text"
        msgs[0].toolCallId shouldBe "call-1"
        msgs[0].toolName shouldBe "search"
    }

    test("TOOL_RESULT with missing metadata produces null toolCallId and toolName") {
        val msgs = PromptBuilder.build(listOf(entry(EntryRole.TOOL_RESULT, "result")))
        msgs[0].toolCallId shouldBe null
        msgs[0].toolName shouldBe null
    }

    test("mixed entry sequence preserves order") {
        val entries = listOf(
            entry(EntryRole.SYSTEM, "sys"),
            entry(EntryRole.USER, "hello"),
            entry(EntryRole.ASSISTANT, "hi"),
            entry(EntryRole.USER, "what's 2+2?"),
            entry(EntryRole.ASSISTANT, "4")
        )
        val msgs = PromptBuilder.build(entries)
        msgs shouldHaveSize 5
        msgs.map { it.role } shouldBe listOf(
            MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT,
            MessageRole.USER, MessageRole.ASSISTANT
        )
    }

    test("ASSISTANT toolCalls field is null for text-only assistant entries") {
        val msgs = PromptBuilder.build(listOf(entry(EntryRole.ASSISTANT, "response")))
        msgs[0].toolCalls shouldBe null
    }
})
