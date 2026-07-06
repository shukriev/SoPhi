package dev.sophi.ai.api

interface SearchProvider {
    val name: String
    suspend fun search(query: String, count: Int): List<SearchResult>
}

data class SearchResult(val title: String, val url: String, val snippet: String)
