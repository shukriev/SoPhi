package dev.sophi.sdk

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.learning.JsonlLog
import dev.sophi.learning.LearningConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.createTempDirectory

private const val TEST_CONTEXT_WINDOW = 100_000

private class StubEmbeddingProvider : EmbeddingProvider {
    override val dimensions = 4
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { FloatArray(dimensions) }
}

private fun stubProvider(): LLMProvider {
    val provider = mockk<LLMProvider>()
    every { provider.stream(any()) } answers { LLMResponse.Text("done", TokenUsage(1, 1)).toStreamFlow() }
    return provider
}

class SophiRuntimeRecordSessionEndTest : FunSpec({

    test("recordSessionEnd writes a completed outcome via the learning plugin") {
        val learningHome = createTempDirectory("sophi-learning-test")
        val rt = RuntimeBuilder().apply {
            provider = stubProvider()
            sessionsDir = createTempDirectory("sophi-sdk-session-end-sessions")
        }.contextWindowTokens(TEST_CONTEXT_WINDOW)
            .learning(LearningConfig(home = learningHome, scope = "/p"))
            .build()

        val sessionId = rt.newSession()
        rt.recordSessionEnd(sessionId)

        JsonlLog(learningHome.resolve("session-outcomes.jsonl")).readAll().single() shouldContain
            "\"outcome\":\"completed\""
    }

    test("recordSessionEnd is a no-op that never throws when neither plugin is configured") {
        val rt = RuntimeBuilder().apply {
            provider = stubProvider()
            sessionsDir = createTempDirectory("sophi-sdk-session-end-sessions")
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).build()

        val sessionId = rt.newSession()
        rt.recordSessionEnd(sessionId) // must not throw
    }

    test("recordSessionEnd with both learning and memory configured does not throw") {
        val learningHome = createTempDirectory("sophi-learning-test")
        val rt = RuntimeBuilder().apply {
            provider = stubProvider()
            sessionsDir = createTempDirectory("sophi-sdk-session-end-sessions")
            memoryHome = createTempDirectory("sophi-sdk-session-end-memory-home")
        }.contextWindowTokens(TEST_CONTEXT_WINDOW)
            .learning(LearningConfig(home = learningHome, scope = "/p"))
            .memory(
                embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://ignored-by-override",
                embeddingProvider = StubEmbeddingProvider()
            )
            .build()

        val sessionId = rt.newSession()
        rt.recordSessionEnd(sessionId) // must not throw

        rt.close()
    }
})
