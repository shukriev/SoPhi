package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.mockk

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderGoalDecompositionTest : FunSpec({
    test("goalDecomposition() registers decompose_goal") {
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).goalDecomposition(tempdir().toPath()).build()

        runtime.toolNames() shouldContain "decompose_goal"
    }

    test("without goalDecomposition(), decompose_goal is not registered") {
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).build()

        runtime.toolNames() shouldNotContain "decompose_goal"
    }
})
