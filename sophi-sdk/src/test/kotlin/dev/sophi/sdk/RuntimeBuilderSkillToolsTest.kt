package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.mockk
import kotlin.io.path.writeText

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderSkillToolsTest : FunSpec({
    test("skillTools() registers skill/install_skill/write_skill, skill only when the registry is non-empty") {
        val globalDir = tempdir().toPath()
        globalDir.resolve("greet.md").writeText("---\ntitle: Greet\ndescription: says hi\n---\nSay hello.")
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).skillTools(globalDir).build()

        val names = runtime.toolNames()
        names shouldContain "skill"
        names shouldContain "install_skill"
        names shouldContain "write_skill"
    }

    test("skillTools() with an empty skills dir does not register skill, but still registers install_skill/write_skill") {
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).skillTools(tempdir().toPath()).build()

        val names = runtime.toolNames()
        names shouldNotContain "skill"
        names shouldContain "install_skill"
        names shouldContain "write_skill"
    }

    test("skillTools() works with no projectDir given") {
        val globalDir = tempdir().toPath()
        globalDir.resolve("greet.md").writeText("---\ntitle: Greet\ndescription: says hi\n---\nSay hello.")
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).skillTools(globalDir).build()

        runtime.toolNames() shouldContain "skill"
    }
})
