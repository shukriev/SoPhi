package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.mockk

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderBuiltinToolsTest : FunSpec({
    test("builtinTools() registers the standard file/shell/search/date tool set") {
        val root = tempdir().toPath()
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).builtinTools(root).build()

        val names = runtime.toolNames()
        names shouldContain "read_file"
        names shouldContain "write_file"
        names shouldContain "grep"
        names shouldContain "glob"
        names shouldContain "edit_file"
        names shouldContain "bash"
        names shouldContain "fetch_url"
        names shouldContain "get_current_datetime"
        names shouldContain "invoke_claude_code"
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
