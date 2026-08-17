package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderScheduleTest : FunSpec({
    test("schedule() opts a task store into the runtime's tool registry") {
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).schedule(tempdir().toPath()).build()

        runtime.toolNames() shouldContain "manage_scheduled_task"
    }

    test("without schedule(), the tool is absent") {
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).build()

        (runtime.toolNames().contains("manage_scheduled_task")) shouldBe false
    }
})
