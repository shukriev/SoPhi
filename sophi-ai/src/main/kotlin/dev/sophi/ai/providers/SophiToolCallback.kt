package dev.sophi.ai.providers

import dev.sophi.ai.api.ToolDefinition
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.definition.ToolDefinition as SpringToolDefinition

/**
 * Declares a Sophi tool's schema to Spring AI so the model knows it exists.
 * [call] is never invoked: AgentLoop executes tools itself via ToolRegistry and
 * reads tool_calls back out of the response (see ChatResponseMapper) — Spring AI's
 * internal tool-execution path is deprecated in this version and isn't used here.
 */
class SophiToolCallback(private val definition: ToolDefinition) : ToolCallback {

    override fun getToolDefinition(): SpringToolDefinition =
        DefaultToolDefinition(definition.name, definition.description, definition.parametersJson)

    override fun call(toolInput: String): String =
        throw UnsupportedOperationException(
            "SophiToolCallback.call() should never be invoked — AgentLoop owns tool execution."
        )
}
