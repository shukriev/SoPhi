package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.context.ContextEngine
import dev.sophi.context.GitProbe
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainAll
import io.mockk.mockk

class RuntimeBuilderContextTest : FunSpec({
    test("context(...) registers the three context tools") {
        val engine = ContextEngine(tempdir().toPath(), GitProbe.none)
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            contextWindowTokens(32_000)
            sessionsDir = tempdir().toPath()
            context(engine) { emptyList() }
        }.build()
        runtime.toolNames() shouldContainAll listOf("search_context", "read_context", "project_map")
        runtime.close()
    }
})
