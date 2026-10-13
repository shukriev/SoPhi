package dev.sophi.sdk

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.ContextContributor
import dev.sophi.extensions.Placement
import dev.sophi.extensions.SophiPlugin
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

class RecordingStreamProvider : LLMProvider {
    val requests = mutableListOf<CompletionRequest>()
    override val name = "recording"
    override suspend fun complete(request: CompletionRequest) = LLMResponse.Text("ok", usage = TokenUsage(0, 0))
    override fun stream(request: CompletionRequest): Flow<StreamEvent> { requests += request; return flowOf(StreamEvent.Content("ok")) }
}

class ContextPlacementTest : FunSpec({
    fun contributor(p: Placement, text: (String) -> String) = object : SophiPlugin, ContextContributor {
        override val name = "c-$p"
        override fun hooks() = emptyList<AgentHook>()
        override val placement = p
        override suspend fun contribute(sessionId: String, userInput: String) = text(userInput)
    }

    test("stable context stays in the system prompt; volatile goes only into this turn's request") {
        val provider = RecordingStreamProvider()
        val runtime = RuntimeBuilder().apply {
            this.provider = provider
            contextWindowTokens(32_000)
            sessionsDir = tempdir().toPath()
            plugin(contributor(Placement.STABLE) { "STABLE-RULES" })
            plugin(contributor(Placement.VOLATILE) { "hint for: $it" })
        }.build()
        val session = runtime.sessionManager.create()
        val s1 = runBlocking { runtime.streamTurn(session, "first question") {} }
        runBlocking { runtime.streamTurn(s1, "second question") {} }

        val (r1, r2) = provider.requests
        r1.systemPrompt!! shouldContain "STABLE-RULES"
        r1.systemPrompt shouldNotContain "hint for"
        r2.systemPrompt shouldBe r1.systemPrompt                       // cache-safe prefix
        r1.messages.last().role shouldBe MessageRole.USER
        r1.messages.last().content shouldStartWith "<context>\nhint for: first question\n</context>\n\nfirst question"
        // history carries the raw input only; the old hint is gone
        r2.messages.first { it.role == MessageRole.USER }.content shouldBe "first question"
        r2.messages.last().content shouldContain "hint for: second question"
        r2.messages.none { it.content.contains("hint for: first question") } shouldBe true
        runtime.sessionManager.load(s1.id).branch().first { it.role.name == "USER" }.content shouldBe "first question"
        runtime.close()
    }

    test("no volatile contribution leaves the user message untouched") {
        val provider = RecordingStreamProvider()
        val runtime = RuntimeBuilder().apply {
            this.provider = provider; contextWindowTokens(32_000); sessionsDir = tempdir().toPath()
        }.build()
        runBlocking { runtime.streamTurn(runtime.sessionManager.create(), "plain") {} }
        provider.requests.single().messages.last().content shouldBe "plain"
        runtime.close()
    }
})
