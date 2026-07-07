package dev.sophi.core.tools

import dev.sophi.ai.api.SearchProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val DEFAULT_COUNT = 5

@Serializable
private data class WebSearchArgs(val query: String, val count: Int? = null)

class WebSearchTool(private val provider: SearchProvider) : Tool {

    override val name = "web_search"
    override val description = "Search the web for a query and return matching page titles/URLs/snippets"
    override val parametersJson = """
        {"type":"object","properties":{"query":{"type":"string","description":"The search query"},"count":{"type":"integer","description":"Number of results to return (default 5)"}},"required":["query"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString<WebSearchArgs>(argumentsJson)
        val results = provider.search(args.query, args.count ?: DEFAULT_COUNT)

        if (results.isEmpty()) return "No results found"

        return results.mapIndexed { index, r ->
            "${index + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
        }.joinToString("\n")
    }
}
