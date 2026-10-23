package dev.sophi.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

const val TOOL_SEARCH_NAME = "tool_search"

private const val DEFAULT_MAX_RESULTS = 5
private const val MAX_RESULTS_LIMIT = 20

/**
 * Collects, for one turn, the deferred tools the model has loaded through [ToolSearchTool], so
 * the agent loop can send their schemas on the next request. Installed where a turn is launched
 * and read from the coroutine context, so two turns never share activations. Without it
 * (outside a turn) a search still answers; it just activates nothing.
 */
class ActivatedTools : AbstractCoroutineContextElement(ActivatedTools) {
    val names: MutableSet<String> = ConcurrentHashMap.newKeySet()

    companion object Key : CoroutineContext.Key<ActivatedTools>
}

class ToolSearchTool(private val registry: ToolRegistry) : Tool {

    override val name = TOOL_SEARCH_NAME
    override val description: String
        get() = "Load tools that are not in your tool list yet. Call it with a few words describing what you need; " +
            "matching tools become callable for the rest of this turn. Not loaded yet: ${registry.deferredNames().joinToString(", ")}"
    override val parametersJson = """
        {"type":"object","properties":{"query":{"type":"string","description":"Words describing the tool you need"},"max_results":{"type":"integer","description":"Maximum number of tools to load (default 5, max 20)"}},"required":["query"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
        val query = args?.get("query")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val limit = (args?.get("max_results")?.jsonPrimitive?.runCatching { int }?.getOrNull() ?: DEFAULT_MAX_RESULTS)
            .coerceIn(1, MAX_RESULTS_LIMIT)
        val words = query.lowercase().split(Regex("[\\s_]+")).filter { it.length >= 2 }

        val matches = registry.deferredNames().mapNotNull { registry.getOrNull(it) }
            .map { tool ->
                val haystack = "${tool.name} ${tool.description}".lowercase()
                tool to words.count { it in haystack }
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Tool, Int>> { it.second }.thenBy { it.first.name })
            .take(limit)
            .map { it.first }

        if (matches.isEmpty()) {
            return "No deferred tool matches \"$query\". Deferred tools: ${registry.deferredNames().joinToString(", ")}"
        }
        coroutineContext[ActivatedTools]?.names?.addAll(matches.map { it.name })
        val header = if (matches.size == 1) "Found 1 tool. It is now callable:"
        else "Found ${matches.size} tools. They are now callable:"
        return (listOf(header) + matches.map { "- ${it.name}: ${it.description}" }).joinToString("\n")
    }
}
