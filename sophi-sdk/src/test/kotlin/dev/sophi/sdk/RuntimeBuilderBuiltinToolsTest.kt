package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.mockk

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderBuiltinToolsTest : FunSpec({
    // The full name list buildBuiltinTools() returns is covered by BuiltinToolsTest.kt and
    // BuildCliRuntimeTest.kt's shouldContainAll — this test's job is only to prove the builder
    // actually registers whatever that function returns, so one representative name suffices.
    test("builtinTools() registers buildBuiltinTools()'s tool set") {
        val root = tempdir().toPath()
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).builtinTools(root).build()

        runtime.toolNames() shouldContain "read_file"
    }

    test("builtinTools() only registers web_search when a Brave API key is given") {
        val root = tempdir().toPath()
        val withoutKey = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).builtinTools(root).build()
        val withKey = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).builtinTools(root, braveApiKey = "test-key").build()

        withoutKey.toolNames() shouldNotContain "web_search"
        withKey.toolNames() shouldContain "web_search"
    }
})
