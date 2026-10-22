package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.store.arcade.EmbeddedArcadeStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
        val rt = builder.contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }.build()

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
            .contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }
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
            .contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                embeddingProvider = StubEmbeddingProvider(shouldFail = false)
            )
            .build()

        rt.memoryPlugin.shouldNotBeNull()
        rt.config.systemPrompt.shouldNotBeNull() shouldContain "## Memory"
    }

    test("memory() with a successful probe also registers list_actionable_patterns") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                embeddingProvider = StubEmbeddingProvider(shouldFail = false)
            )
            .build()

        rt.toolNames() shouldContain "list_actionable_patterns"
    }

    test("memory() left uncalled does not register list_actionable_patterns") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder.contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }.build()

        rt.toolNames() shouldNotContain "list_actionable_patterns"
    }

    test("memory() with a successful probe also registers list_open_commitments") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                embeddingProvider = StubEmbeddingProvider(shouldFail = false)
            )
            .build()

        rt.toolNames() shouldContain "list_open_commitments"
    }

    test("memory() left uncalled does not register list_open_commitments") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder.contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }.build()

        rt.toolNames() shouldNotContain "list_open_commitments"
    }

    test("memory() with a successful probe also registers list_habits") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                embeddingProvider = StubEmbeddingProvider(shouldFail = false)
            )
            .build()

        rt.toolNames() shouldContain "list_habits"
    }

    test("memory() left uncalled does not register list_habits") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder.contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }.build()

        rt.toolNames() shouldNotContain "list_habits"
    }

    test("memory() with a failing probe disables memory and fires onWarning instead of throwing") {
        val warnings = mutableListOf<String>()
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }
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
        // Two processes no longer conflict over memory (they share one server, ADR-041 M6), but an
        // open can still fail (migration locked, corrupt data). A plain file where a directory is
        // expected reproduces any such open failure deterministically.
        val warnings = mutableListOf<String>()
        val blockedHome = createTempDirectory("sophi-sdk-memory-home-test").resolve("blocked-by-a-file")
        blockedHome.writeText("not a directory")
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = blockedHome
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                onWarning = { warnings.add(it) }, embeddingProvider = StubEmbeddingProvider(shouldFail = false)
            )
            .build()

        rt.memoryPlugin.shouldBeNull()
        rt.config.systemPrompt shouldBe DefaultPrompt.BASE
        warnings.single() shouldContain "memory: disabled — couldn't open the memory database"
        warnings.single() shouldNotContain "only one process"
    }

    test("memory() on a shared root whose legacy data can't move disables memory and fires onWarning instead of crashing") {
        // An older Sophi still holding the unmigrated embedded dir makes PalaceStore.shared throw IllegalStateException.
        val warnings = mutableListOf<String>()
        val home = createTempDirectory("sophi-sdk-memory-home-test")
        EmbeddedArcadeStore.open(home).use { old ->
            old.ensureSchema(vertexTypes = listOf("Memory"))
            val builder = RuntimeBuilder()
            builder.provider = stubProvider()
            builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
            builder.memoryHome = home
            builder.memorySharedRoot = createTempDirectory("sophi-sdk-memory-root-test")
            val rt = builder
                .contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }
                .memory(
                    embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                    onWarning = { warnings.add(it) }, embeddingProvider = StubEmbeddingProvider(shouldFail = false)
                )
                .build()
            try {
                rt.memoryPlugin.shouldBeNull()
                warnings.single() shouldContain "memory: disabled — couldn't open the memory database at $home"
                warnings.single() shouldContain "an older Sophi may still be running"
            } finally { rt.close() }
        }
    }

    test("close() closes the memory plugin without throwing") {
        val builder = RuntimeBuilder()
        builder.provider = stubProvider()
        builder.sessionsDir = createTempDirectory("sophi-sdk-memory-test")
        builder.memoryHome = createTempDirectory("sophi-sdk-memory-home-test")
        val rt = builder
            .contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                embeddingProvider = StubEmbeddingProvider(shouldFail = false)
            )
            .build()

        rt.close()
    }
})
