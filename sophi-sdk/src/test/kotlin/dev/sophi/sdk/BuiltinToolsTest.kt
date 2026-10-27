package dev.sophi.sdk

import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.tools.isToolError
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class BuiltinToolsTest : FunSpec({
    test("buildBuiltinTools registers get_current_datetime") {
        val names = buildBuiltinTools(tempdir().toPath(), braveApiKey = null).map { it.name }
        names shouldContain "get_current_datetime"
    }

    test("buildBuiltinTools registers invoke_claude_code") {
        val names = buildBuiltinTools(tempdir().toPath(), braveApiKey = null).map { it.name }
        names shouldContain "invoke_claude_code"
    }

    test("decompose_goal is a session-bound tool, so it is absent from the static builtin set") {
        val names = buildBuiltinTools(tempdir().toPath(), braveApiKey = null).map { it.name }
        names.contains("decompose_goal") shouldBe false
    }
    test("every builtin tool with required arguments reports a call without them as failed") {
        // The loop only knows a call failed if the tool throws or returns an "Error: " result;
        // a tool that words its error any other way is logged as a success.
        val tools = buildBuiltinTools(tempdir().toPath(), braveApiKey = null)
            .filter { "required" in Json.parseToJsonElement(it.parametersJson).jsonObject }
        tools.size shouldBeGreaterThan 5
        val unflagged = tools.filterNot { tool ->
            runCatching { runBlocking { tool.execute("{}") } }.fold({ isToolError(it) }, { true })
        }
        unflagged.map { it.name } shouldBe emptyList()
    }

    test("builtinMcpServer audits each call to the given sink") {
        val entries = mutableListOf<AuditEntry>()
        val server = builtinMcpServer(setOf("get_current_datetime"), root = tempdir().toPath(), audit = { entries += it })
        val connection = mockk<ClientConnection> { every { sessionId } returns "c" }
        runBlocking {
            server.tools.getValue("get_current_datetime").handler.invoke(
                connection, CallToolRequest(CallToolRequestParams("get_current_datetime", JsonObject(emptyMap())))
            )
        }
        entries.single().tool shouldBe "get_current_datetime"
        entries.single().rule shouldBe "safe"
    }
})
