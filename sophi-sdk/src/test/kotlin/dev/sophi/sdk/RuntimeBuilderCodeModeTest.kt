package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.mockk

class RuntimeBuilderCodeModeTest : FunSpec({
    fun builder() = RuntimeBuilder().apply {
        provider = mockk<LLMProvider>()
        sessionsDir = tempdir().toPath()
        model = "test-model"
    }.contextWindowTokens(100_000)

    test("code_mode is off unless codeMode() is called") {
        builder().build().toolNames() shouldNotContain "code_mode"
        builder().codeMode().build().toolNames() shouldContain "code_mode"
    }
})
