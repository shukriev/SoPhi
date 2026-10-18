package dev.sophi.context

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.system1.Judgments
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class FakeLlm(private val reply: String?, private val fail: Boolean = false) : LLMProvider {
    override val name = "fake"
    val requests = mutableListOf<CompletionRequest>()
    override suspend fun complete(request: CompletionRequest): LLMResponse {
        requests += request
        if (fail) error("down")
        return LLMResponse.Text(reply ?: "", TokenUsage(1, 1))
    }
    override fun stream(request: CompletionRequest): Flow<StreamEvent> = emptyFlow()
}

class FileSummariserTest : FunSpec({
    test("one clean line, no reasoning, the context-summary judgment, path in the prompt") {
        val p = FakeLlm("<think>hmm</think>Starts the hub\nserver <b>.")
        runBlocking { FileSummariser(p, "m").summarise("a/Hub.kt", "class Hub") } shouldBe "Starts the hub server ‹b›."
        val r = p.requests.single()
        r.reasoningEffort shouldBe "none"; r.judgment shouldBe Judgments.CONTEXT_SUMMARY; r.model shouldBe "m"
        r.messages.last().content shouldContain "a/Hub.kt"
    }
    test("blank or failing replies give null") {
        runBlocking { FileSummariser(FakeLlm("  "), "m").summarise("a", "b") }.shouldBeNull()
        runBlocking { FileSummariser(FakeLlm(null, fail = true), "m").summarise("a", "b") }.shouldBeNull()
    }
})
