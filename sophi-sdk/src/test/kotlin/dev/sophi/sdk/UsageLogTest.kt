package dev.sophi.sdk

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class UsageLogTest : FunSpec({
    test("each model request appends one usage line with cache reads") {
        val log = tempdir().toPath().resolve("usage.jsonl")
        val provider = object : LLMProvider {
            override val name = "u"
            override suspend fun complete(request: CompletionRequest) = LLMResponse.Text("ok", usage = TokenUsage(0, 0))
            override fun stream(request: CompletionRequest) = flowOf(StreamEvent.Content("ok"), StreamEvent.Usage(TokenUsage(120, 5, cacheReadTokens = 100)))
        }
        val runtime = RuntimeBuilder().apply {
            this.provider = provider; contextWindowTokens(32_000); sessionsDir = tempdir().toPath(); usageLogPath = log
        }.build()
        runBlocking { runtime.streamTurn(runtime.sessionManager.create(), "hi") {} }
        val line = Files.readAllLines(log).single()
        line shouldContain "\"kind\":\"turn\""; line shouldContain "\"input\":120"; line shouldContain "\"cacheRead\":100"
        runtime.close()
    }
})
