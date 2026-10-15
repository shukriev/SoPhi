package dev.sophi.core.context

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorkingSetTest : FunSpec({
    fun call(id: String, name: String, args: String) = """{"id":"$id","name":"$name","argumentsJson":${Json.encodeToString(args)}}"""
    fun session(): List<SessionEntry> {
        val s = AgentSession(id = "ws")
        s.append(EntryRole.USER, "fix the build")
        s.append(EntryRole.ASSISTANT, "", mapOf("replay" to "false", "toolCalls" to "[${call("1", "read_file", """{"path":"a/B.kt"}""")},${call("2", "bash", """{"command":"mvn -q test"}""")}]"))
        s.append(EntryRole.TOOL_RESULT, "class B", mapOf("replay" to "false", "toolCallId" to "1", "toolName" to "read_file"))
        s.append(EntryRole.TOOL_RESULT, "BUILD FAILURE\nCommand exited with code 1", mapOf("replay" to "false", "toolCallId" to "2", "toolName" to "bash"))
        s.append(EntryRole.ASSISTANT, "", mapOf("replay" to "false", "toolCalls" to "[${call("3", "edit_file", """{"path":"a/B.kt","old":"x","new":"y"}""")},${call("4", "grep", """{"pattern":"TODO"}""")}]"))
        s.append(EntryRole.TOOL_RESULT, "Error: old string not found", mapOf("replay" to "false", "toolCallId" to "3", "toolName" to "edit_file"))
        s.append(EntryRole.TOOL_RESULT, "a/C.kt:3: TODO", mapOf("replay" to "false", "toolCallId" to "4", "toolName" to "grep"))
        return s.branch()
    }

    test("files, commands with exit codes, searches and errors are derived exactly from tool rounds") {
        val ws = WorkingSet.fromEntries(session())
        ws.files shouldBe listOf(FileTouch("a/B.kt", reads = 1, writes = 1, lastAction = "edit"))
        ws.commands shouldBe listOf(CommandRun("mvn -q test", 1))
        ws.searches shouldBe listOf("TODO")
        ws.errors shouldBe listOf(ToolError("edit_file", "Error: old string not found"))
        ws.filesSection() shouldContain "a/B.kt — read 1, written 1, last: edit"
        ws.commandsSection() shouldContain "mvn -q test → exit 1"
    }

    test("fromMessages gives the same set as fromEntries for the same rounds") {
        val entries = session()
        val messages = entries.flatMap { e -> when {
            e.role == EntryRole.ASSISTANT && e.metadata["toolCalls"] != null ->
                listOf(Message(MessageRole.ASSISTANT, "", toolCalls = Json.decodeFromString<List<JsonObject>>(e.metadata["toolCalls"]!!)
                    .map { ToolCall(it["id"]!!.jsonPrimitive.content, it["name"]!!.jsonPrimitive.content, it["argumentsJson"]!!.jsonPrimitive.content) }))
            e.role == EntryRole.TOOL_RESULT -> listOf(Message(MessageRole.TOOL, e.content, toolCallId = e.metadata["toolCallId"], toolName = e.metadata["toolName"]))
            else -> emptyList()
        } }
        WorkingSet.fromMessages(messages) shouldBe WorkingSet.fromEntries(entries)
    }

    test("unparseable arguments and unknown tools never throw; empty sections say (none)") {
        val s = AgentSession(id = "x")
        s.append(EntryRole.ASSISTANT, "", mapOf("toolCalls" to """[{"id":"1","name":"read_file","argumentsJson":"not json"}]"""))
        s.append(EntryRole.TOOL_RESULT, "ok", mapOf("toolCallId" to "1"))
        val ws = WorkingSet.fromEntries(s.branch())
        ws.files shouldBe emptyList()
        ws.commandsSection() shouldBe "(none)"
    }
})
