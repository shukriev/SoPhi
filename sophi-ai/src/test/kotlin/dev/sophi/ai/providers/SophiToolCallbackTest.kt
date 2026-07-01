package dev.sophi.ai.providers

import dev.sophi.ai.api.ToolDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SophiToolCallbackTest : FunSpec({
    test("getToolDefinition maps name, description and parametersJson") {
        val callback = SophiToolCallback(ToolDefinition("search", "Searches the web", """{"type":"object"}"""))

        callback.toolDefinition.name() shouldBe "search"
        callback.toolDefinition.description() shouldBe "Searches the web"
        callback.toolDefinition.inputSchema() shouldBe """{"type":"object"}"""
    }

    test("call() throws because AgentLoop owns tool execution, not Spring AI") {
        val callback = SophiToolCallback(ToolDefinition("search", "Searches the web", "{}"))

        shouldThrow<UnsupportedOperationException> { callback.call("{}") }
    }
})
