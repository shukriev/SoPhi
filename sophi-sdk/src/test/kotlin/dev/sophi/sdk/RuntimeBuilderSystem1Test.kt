package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.system1.DualSystemProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import java.nio.file.Files

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderSystem1Test : FunSpec({
    test("system1() wraps the provider the runtime hands to every component") {
        val home = tempdir().toPath()
        Files.writeString(home.resolve("system1.json"), """{"baseUrl":"http://localhost:1234/v1","model":"small"}""")
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>(relaxed = true)
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }.system1(home).build()

        runtime.provider.shouldBeInstanceOf<DualSystemProvider>()
    }

    test("without system1(), the provider is not wrapped") {
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>(relaxed = true)
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).apply { model = "test-model" }.build()

        (runtime.provider is DualSystemProvider) shouldBe false
    }
})
