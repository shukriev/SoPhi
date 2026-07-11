package dev.sophi.learning.export

import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*

class SftBuilderTest : FunSpec({
    fun e(role: EntryRole, content: String, meta: Map<String, String> = emptyMap()) =
        SessionEntry("id", null, role, content, 1L, meta)
    val entries = listOf(
        e(EntryRole.USER, "read a.txt with secret dev@example.com"),
        e(EntryRole.ASSISTANT, "", mapOf("replay" to "false",
            "toolCalls" to """[{"id":"c1","name":"file_read","argumentsJson":"{\"path\":\"a.txt\"}"}]""")),
        e(EntryRole.TOOL_RESULT, "contents", mapOf("replay" to "false", "toolCallId" to "c1", "toolName" to "file_read")),
        e(EntryRole.ASSISTANT, "done"))

    test("builds chat messages with tool_calls, tool role, system prompt, and redaction applied") {
        val line = SftBuilder(Redactor()::redact).build(entries, systemPrompt = "be helpful")
        val messages = Json.parseToJsonElement(line).jsonObject.getValue("messages").jsonArray
        messages[0].jsonObject.getValue("role").jsonPrimitive.content shouldBe "system"
        messages[1].jsonObject.getValue("content").jsonPrimitive.content shouldContain "[REDACTED:email]"
        val toolCall = messages[2].jsonObject.getValue("tool_calls").jsonArray[0].jsonObject
        toolCall.getValue("function").jsonObject.getValue("name").jsonPrimitive.content shouldBe "file_read"
        messages[3].jsonObject.getValue("role").jsonPrimitive.content shouldBe "tool"
        messages[3].jsonObject.getValue("tool_call_id").jsonPrimitive.content shouldBe "c1"
        messages[4].jsonObject.getValue("content").jsonPrimitive.content shouldBe "done"
    }

    test("legacy session without tool rounds or snapshot: conversation-only, no system message") {
        val line = SftBuilder { it }.build(listOf(
            e(EntryRole.USER, "hi"), e(EntryRole.ASSISTANT, "hello")), systemPrompt = null)
        val messages = Json.parseToJsonElement(line).jsonObject.getValue("messages").jsonArray
        messages.size shouldBe 2
    }

    test("buildPerTurn emits one prefix example per assistant reply") {
        SftBuilder { it }.buildPerTurn(entries, null).size shouldBe 2
    }
})
