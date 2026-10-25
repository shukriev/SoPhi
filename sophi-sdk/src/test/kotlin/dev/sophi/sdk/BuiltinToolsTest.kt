package dev.sophi.sdk

import dev.sophi.core.audit.AuditEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

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
