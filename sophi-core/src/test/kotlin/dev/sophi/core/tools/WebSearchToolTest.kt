package dev.sophi.core.tools

import dev.sophi.ai.api.SearchProvider
import dev.sophi.ai.api.SearchResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class WebSearchToolTest : FunSpec({

    fun fakeProvider(results: List<SearchResult>, capturedCount: MutableList<Int> = mutableListOf()) =
        object : SearchProvider {
            override val name = "fake"
            override suspend fun search(query: String, count: Int): List<SearchResult> {
                capturedCount.add(count)
                return results
            }
        }

    test("execute() formats results as a numbered list") {
        val tool = WebSearchTool(fakeProvider(listOf(
            SearchResult("Kotlin Docs", "https://kotlinlang.org", "The Kotlin language")
        )))

        val result = runBlocking { tool.execute("""{"query":"kotlin"}""") }

        result shouldContain "1. Kotlin Docs"
        result shouldContain "https://kotlinlang.org"
        result shouldContain "The Kotlin language"
    }

    test("execute() returns 'No results found' for an empty result list") {
        val tool = WebSearchTool(fakeProvider(emptyList()))
        val result = runBlocking { tool.execute("""{"query":"zzz"}""") }
        result shouldBe "No results found"
    }

    test("execute() defaults count to 5 when not specified") {
        val counts = mutableListOf<Int>()
        val tool = WebSearchTool(fakeProvider(emptyList(), counts))
        runBlocking { tool.execute("""{"query":"kotlin"}""") }
        counts shouldBe listOf(5)
    }

    test("execute() passes an explicit count through to the provider") {
        val counts = mutableListOf<Int>()
        val tool = WebSearchTool(fakeProvider(emptyList(), counts))
        runBlocking { tool.execute("""{"query":"kotlin","count":10}""") }
        counts shouldBe listOf(10)
    }

    test("name is web_search") {
        WebSearchTool(fakeProvider(emptyList())).name shouldBe "web_search"
    }
})
