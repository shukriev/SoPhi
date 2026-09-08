package dev.sophi.sdk

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
import kotlin.io.path.writeText

private const val TEST_CONTEXT_WINDOW = 100_000

private fun stubProvider(): LLMProvider {
    val provider = mockk<LLMProvider>()
    every { provider.stream(any()) } answers {
        LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow()
    }
    return provider
}

class RuntimeBuilderMemoryTest : FunSpec({

    test("memory() left uncalled leaves memoryPlugin null and the prompt at just the default") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder.contextWindowTokens(TEST_CONTEXT_WINDOW).build()

        rt.memoryPlugin.shouldBeNull()
        rt.config.systemPrompt shouldBe DefaultPrompt.BASE
    }

    test("the default prompt comes first, ahead of a caller's own prompt and the memory section") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        builder.systemPrompt = "custom instructions"
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                embeddingProvider = StubEmbeddingProvider(shouldFail = false)
            )
            .build()

        val prompt = rt.config.systemPrompt.shouldNotBeNull()
        val basePos = prompt.indexOf(DefaultPrompt.BASE)
        val customPos = prompt.indexOf("custom instructions")
        val memoryPos = prompt.indexOf("## Memory")
        (basePos >= 0 && basePos < customPos && customPos < memoryPos) shouldBe true
    }

    test("memory() with a successful probe registers a MemoryPlugin and appends the memory prompt section") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                embeddingProvider = StubEmbeddingProvider(shouldFail = false)
            )
            .build()

        rt.memoryPlugin.shouldNotBeNull()
        rt.config.systemPrompt.shouldNotBeNull() shouldContain "## Memory"
    }

    test("memory() with a failing probe disables memory and fires onWarning instead of throwing") {
        val warnings = mutableListOf<String>()
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://unreachable",
                onWarning = { warnings.add(it) }, embeddingProvider = StubEmbeddingProvider(shouldFail = true)
            )
            .build()

        rt.memoryPlugin.shouldBeNull()
        rt.config.systemPrompt shouldBe DefaultPrompt.BASE
        warnings.single() shouldContain "memory: disabled — embeddings endpoint unreachable"
    }

    test("memory() with a database that fails to open disables memory and fires onWarning instead of crashing") {
        // Reproduces the "Error on creating new database instance" bug: ArcadeDB locks its
        // database directory to one process, so a second Sophi process (CLI, another companion
        // instance) pointed at the same memoryHome throws when this opens it. A plain file where
        // a directory is expected reproduces any such open failure deterministically, without
        // needing a second process actually holding the lock.
        val warnings = mutableListOf<String>()
        val blockedHome = createTempDirectory("sophi-sdk-memory-home-test").resolve("blocked-by-a-file")
        blockedHome.writeText("not a directory")
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = blockedHome
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                onWarning = { warnings.add(it) }, embeddingProvider = StubEmbeddingProvider(shouldFail = false)
            )
            .build()

        rt.memoryPlugin.shouldBeNull()
        rt.config.systemPrompt shouldBe DefaultPrompt.BASE
        warnings.single() shouldContain "memory: disabled — couldn't open the memory database"
    }

    test("close() closes the memory plugin without throwing") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW)
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                embeddingProvider = StubEmbeddingProvider(shouldFail = false)
            )
            .build()

        rt.close()
    }
})
