package dev.sophi.memory.tools

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.memory.jane.JanesPalace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** "Why do you believe this?" for one recalled memory; looks in the personal palace, then the household's. */
class ExplainMemoryTool(private val palaces: List<JanesPalace>) : Tool {
    override val name = "explain_memory"
    override val description = "Say where a remembered fact came from: the chat or meeting it was heard in, " +
        "the user's own words, whether it was stated or inferred, how sure SoPhi is, and when it was saved. " +
        "Use it when the user asks why you think something about them. Pass the id shown on the memory line."
    override val parametersJson =
        """{"type":"object","properties":{"id":{"type":"string","description":"The memory's id, e.g. mem_..."}},"required":["id"]}"""
    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String {
        val id = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
        return id?.let { i -> palaces.firstNotNullOfOrNull { it.explain(i) } } ?: "No memory with that id."
    }
}
