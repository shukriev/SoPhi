package dev.sophi.sdk

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.learning.Lesson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

private class NoopEmbeddingProvider : EmbeddingProvider {
    override val dimensions = 4
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { FloatArray(dimensions) }
}

class RuntimeBuilderLearningTest : FunSpec({
    test("learning() with an embeddingProvider registers a LearningPlugin whose contribute() runs") {
        val provider = mockk<dev.sophi.ai.api.LLMProvider>()
        every { provider.stream(any()) } answers { LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow() }
        val builder = RuntimeBuilder()
        builder.provider = provider
        builder.sessionsDir = createTempDirectory("sophi-sdk-learning-test")
        val learningHome = createTempDirectory("sophi-sdk-learning-home-test")
        val rt = builder.contextWindowTokens(TEST_CONTEXT_WINDOW)
            .learning(dev.sophi.learning.LearningConfig(home = learningHome, scope = "/p"), NoopEmbeddingProvider())
            .build()

        rt.learningPlugin!!.lessonStore.add(Lesson("les_1", 1L, "/p", "s", "database rollback plan", "approach"))
        val rendered = kotlinx.coroutines.runBlocking {
            dev.sophi.extensions.PluginRegistry().register(rt.learningPlugin!!)
                .collectContext("s1", "database migration").single()
        }
        rendered shouldContain "database rollback plan"
    }
})
