package dev.sophi.ai.providers

import dev.sophi.ai.api.SearchProvider
import dev.sophi.ai.api.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val BRAVE_SEARCH_ENDPOINT = "https://api.search.brave.com/res/v1/web/search"

@Serializable
private data class BraveWebResult(val title: String = "", val url: String = "", val description: String = "")

@Serializable
private data class BraveWebSection(val results: List<BraveWebResult> = emptyList())

@Serializable
private data class BraveSearchResponse(val web: BraveWebSection? = null)

class BraveSearchProvider(
    private val apiKey: String,
    override val name: String = "brave",
    private val httpClient: HttpClient = HttpClient.newHttpClient()
) : SearchProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, count: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val uri = URI.create("$BRAVE_SEARCH_ENDPOINT?q=$encodedQuery&count=$count")
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Brave Search API returned HTTP ${response.statusCode()}" }

        val parsed = json.decodeFromString<BraveSearchResponse>(response.body())
        parsed.web?.results.orEmpty().map { SearchResult(it.title, it.url, it.description) }
    }
}
