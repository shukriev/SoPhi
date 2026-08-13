package dev.sophi.sdk

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

private class FakeEmbeddingProvider(private val shouldFail: Boolean) : EmbeddingProvider {
    override val dimensions = 4
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (shouldFail) error("no route to embeddings host")
        return texts.map { FloatArray(dimensions) }
    }
}

private fun stubProvider(): LLMProvider {
    val provider = mockk<LLMProvider>()
    every { provider.stream(any()) } answers {
        LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
    }
    return provider
}

class RuntimeBuilderMemoryTest : FunSpec({

    test("memory() left uncalled leaves memoryPlugin null and the prompt untouched") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        val rt = builder.contextWindowTokens(TEST_CONTEXT_WINDOW).build()

        rt.memoryPlugin.shouldBeNull()
        rt.config.systemPrompt shouldBe null
    }

    test("memory() with a successful probe registers a MemoryPlugin and appends the memory prompt section") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.embeddingProviderOverride = FakeEmbeddingProvider(shouldFail = false)
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .memory(embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override")
            .build()

        rt.memoryPlugin.shouldNotBeNull()
        rt.config.systemPrompt.shouldNotBeNull() shouldContain "## Memory"
    }

    test("memory() with a failing probe disables memory and fires onWarning instead of throwing") {
        val warnings = mutableListOf<String>()
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.embeddingProviderOverride = FakeEmbeddingProvider(shouldFail = true)
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .memory(embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://unreachable", onWarning = { warnings.add(it) })
            .build()

        rt.memoryPlugin.shouldBeNull()
        rt.config.systemPrompt shouldBe null
        warnings.single() shouldContain "memory: disabled — embeddings endpoint unreachable"
    }

    test("close() closes the memory plugin without throwing") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.embeddingProviderOverride = FakeEmbeddingProvider(shouldFail = false)
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .memory(embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override")
            .build()

        rt.close()
    }
})
