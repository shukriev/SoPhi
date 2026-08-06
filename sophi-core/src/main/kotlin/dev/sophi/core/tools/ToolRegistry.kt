package dev.sophi.core.tools

import dev.sophi.ai.api.ToolDefinition

class ToolRegistry {
    private val tools: MutableMap<String, Tool> = mutableMapOf()

    fun register(tool: Tool): ToolRegistry {
        tools[tool.name] = tool
        return this
    }

    fun unregister(name: String): ToolRegistry {
        tools.remove(name)
        return this
    }

    fun get(name: String): Tool =
        tools[name] ?: throw NoSuchElementException("Tool not found: $name")

    fun getOrNull(name: String): Tool? = tools[name]

    fun definitions(): List<ToolDefinition> =
        tools.values.map { ToolDefinition(it.name, it.description, it.parametersJson) }

    fun names(): List<String> = tools.keys.sorted()

    fun subset(names: List<String>): ToolRegistry {
        val filtered = ToolRegistry()
        names.forEach { name -> tools[name]?.let { filtered.register(it) } }
        return filtered
    }
}
