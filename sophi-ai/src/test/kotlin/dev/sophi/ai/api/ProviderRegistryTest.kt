package dev.sophi.ai.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ProviderRegistryTest : FunSpec({
    fun stub(providerName: String) = object : LLMProvider {
        override val name = providerName
        override suspend fun complete(request: CompletionRequest): LLMResponse =
            LLMResponse.Error("stub")
        override fun stream(request: CompletionRequest): Flow<String> = flowOf()
    }

    test("get() returns the provider registered under that name") {
        val p = stub("claude")
        val registry = ProviderRegistry(listOf(p))
        registry.get("claude") shouldBe p
    }

    test("get() throws NoSuchElementException for unknown name") {
        val registry = ProviderRegistry(listOf(stub("claude")))
        shouldThrow<NoSuchElementException> { registry.get("unknown") }
    }

    test("names() returns sorted list of registered provider names") {
        val registry = ProviderRegistry(listOf(stub("openai"), stub("claude"), stub("ollama")))
        registry.names() shouldContainExactly listOf("claude", "ollama", "openai")
    }

    test("last registration wins on duplicate names") {
        val first = stub("claude")
        val second = stub("claude")
        val registry = ProviderRegistry(listOf(first, second))
        registry.get("claude") shouldBe second
    }
})
