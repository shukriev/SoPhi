package dev.sophi.core.prompt

import dev.sophi.ai.api.MessageRole
import dev.sophi.core.session.AgentSession
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

    test("entries marked replay=false are excluded from prompt messages") {
        val session = AgentSession(id = "p1")
        session.append(EntryRole.USER, "hi")
        session.append(EntryRole.ASSISTANT, "", mapOf("replay" to "false", "toolCalls" to "[]"))
        session.append(EntryRole.TOOL_RESULT, "raw", mapOf("replay" to "false", "toolCallId" to "c", "toolName" to "t"))
        session.append(EntryRole.ASSISTANT, "answer")

        val messages = PromptBuilder.build(session.branch())
        messages.map { it.content } shouldBe listOf("hi", "answer")
    }

    test("a stopped turn is replayed as a neutral note, not a marker the model could copy as an answer") {
        val msgs = PromptBuilder.build(listOf(
            entry(EntryRole.ASSISTANT, "[Stopped early: 3 consecutive tool-call rounds failed in a row]", mapOf("stopReason" to "LoopGuard")),
            entry(EntryRole.ASSISTANT, "[Stopped by the user]", mapOf("stopReason" to "UserStopped")),
            entry(EntryRole.ASSISTANT, "\n\n[Stopped early: 3 consecutive tool-call rounds failed in a row]"), // one the model wrote itself
            entry(EntryRole.ASSISTANT, "Done — archived 12 emails."),
        ))
        msgs.take(3).forEach {
            it.role shouldBe MessageRole.ASSISTANT
            it.content.contains("[Stopped") shouldBe false
            it.content.startsWith("(") shouldBe true
        }
        msgs[0].content.contains("3 consecutive tool-call rounds failed") shouldBe true
        msgs[1].content.contains("the user stopped it") shouldBe true
        msgs[3].content shouldBe "Done — archived 12 emails."
    }

    // A steer applied after a tool round is saved after that round, whose entries are replay=false.
    // Replayed later it would follow the turn's input directly, and strict chat templates
    // (Gemma/Mistral) reject two USER messages in a row.
    test("a steer saved after a tool round replays merged into the turn's input") {
        val noReplay = mapOf("replay" to "false")
        val msgs = PromptBuilder.build(listOf(
            entry(EntryRole.USER, "install deps"),
            entry(EntryRole.ASSISTANT, "", noReplay),
            entry(EntryRole.TOOL_RESULT, "npm: not found", noReplay),
            entry(EntryRole.USER, "try yarn", mapOf("steer" to "true")),
            entry(EntryRole.ASSISTANT, "Installed with yarn."),
        ))
        msgs.map { it.role } shouldBe listOf(MessageRole.USER, MessageRole.ASSISTANT)
        msgs[0].content shouldBe "install deps\n\ntry yarn"
    }

    val shot = dev.sophi.ai.api.ImageRef("/tmp/a.png")
    val other = dev.sophi.ai.api.ImageRef("/tmp/b.jpg")

    test("user entry images become Message images") {
        val msgs = PromptBuilder.build(listOf(entry(EntryRole.USER, "look", dev.sophi.core.session.imagesMetadata(listOf(shot)))))
        msgs.single().images shouldBe listOf(shot)
    }

    test("adjacent user merge keeps both entries' images in order") {
        val msgs = PromptBuilder.build(listOf(
            entry(EntryRole.USER, "one", dev.sophi.core.session.imagesMetadata(listOf(shot))),
            entry(EntryRole.USER, "two", dev.sophi.core.session.imagesMetadata(listOf(other))),
        ))
        msgs.single().content shouldBe "one\n\ntwo"
        msgs.single().images shouldBe listOf(shot, other)
    }

    test("bad images metadata reads as none") {
        PromptBuilder.build(listOf(entry(EntryRole.USER, "look", mapOf("images" to "not json")))).single().images shouldBe emptyList()
    }
})
