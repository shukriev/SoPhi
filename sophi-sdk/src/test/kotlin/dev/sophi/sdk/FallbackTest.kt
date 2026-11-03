package dev.sophi.sdk

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.TurnEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.ConnectException

/** Answers "ok from <model>", except for the models in [down], which fail with [error] before any output. */
private class FakeProvider(
    private val down: Set<String> = emptySet(),
    private val error: () -> Throwable = { IllegalStateException("LLM stream error", ConnectException("Connection refused")) },
) : LLMProvider {
    val models = mutableListOf<String>()
    override val name = "fake"
    override suspend fun complete(request: CompletionRequest): LLMResponse {
        models += request.model
        return if (request.model in down) LLMResponse.Error("failed", error()) else LLMResponse.Text("ok from ${request.model}", TokenUsage(1, 0))
    }
    override fun stream(request: CompletionRequest) = flow {
        models += request.model
        if (request.model in down) throw error()
        emit(StreamEvent.Content("ok from ${request.model}"))
        emit(StreamEvent.Usage(TokenUsage(1, 0)))
    }
}

/** Model `cloud` costs $1 per input token. */
private val CATALOG = ModelCatalog.parse("""{"anthropic":{"cloud":{"i":1000000,"p":0}}}""")

private fun req(text: String = "hi") = CompletionRequest(listOf(Message(MessageRole.USER, text)), model = "a")

class FallbackTest : FunSpec({
    fun runtime(p: LLMProvider, vararg chain: FallbackModel) = RuntimeBuilder().apply {
        provider = p; contextWindowTokens(32_000); model = "a"; sessionsDir = tempdir().toPath(); usageLogPath = null
        fallback(*chain)
    }.build()

    test("with no chain, an unavailable model fails the turn as before") {
        val rt = runtime(FakeProvider(down = setOf("a")))
        shouldThrow<IllegalStateException> { runBlocking { rt.streamTurn(rt.sessionManager.create(), "hi") {} } }
        rt.close()
    }

    test("an unavailable model hands the turn to the next one, and the turn says so once") {
        val p = FakeProvider(down = setOf("a"))
        val rt = runtime(p, FallbackModel("b", 32_000))
        val events = mutableListOf<TurnEvent.ModelFallback>()
        val s = runBlocking { rt.streamTurn(rt.sessionManager.create(), "hi") { if (it is TurnEvent.ModelFallback) events += it } }
        s.branch().last().content shouldBe "ok from b"
        events.size shouldBe 1
        events[0].from shouldBe "a"
        events[0].to shouldBe "b"
        events[0].reason shouldContain "answering"
        rt.close()
    }

    test("the rest of the turn stays on the fallback; the next turn tries the model again") {
        val p = FakeProvider(down = setOf("a"))
        val m = MeteredProvider.wrap(p, "a", null, priced = false, catalogProvider = null)
        m.fallbacks = listOf(FallbackModel("b", 32_000))
        suspend fun turn() = withContext(FallbackTurn {}) { m.stream(req()).collect(); m.complete(req()) }
        runBlocking { turn(); turn() }
        p.models shouldBe listOf("a", "b", "b", "a", "b", "b")
    }

    test("a fault in the request itself never falls back") {
        val p = FakeProvider(down = setOf("a")) { IllegalStateException("LLM stream error", IllegalArgumentException("bad tool schema")) }
        val rt = runtime(p, FallbackModel("b", 32_000))
        shouldThrow<IllegalStateException> { runBlocking { rt.streamTurn(rt.sessionManager.create(), "hi") {} } }
        p.models.none { it == "b" } shouldBe true
        rt.close()
    }

    test("a failure after output started is not retried on another model") {
        val broken = object : LLMProvider {
            override val name = "broken"
            override suspend fun complete(request: CompletionRequest) = error("unused")
            override fun stream(request: CompletionRequest) = flow {
                emit(StreamEvent.Content("partial"))
                throw IllegalStateException("LLM stream error", ConnectException("reset"))
            }
        }
        val fb = FakeProvider()
        val m = MeteredProvider.wrap(broken, "a", null, priced = false, catalogProvider = null)
        m.fallbacks = listOf(FallbackModel("b", 32_000, provider = fb))
        shouldThrow<IllegalStateException> { runBlocking { m.stream(req()).collect() } }
        fb.models shouldBe emptyList()
    }

    test("a fallback whose window can't hold the request stops clearly instead of overflowing it") {
        val fb = FakeProvider()
        val m = MeteredProvider.wrap(FakeProvider(down = setOf("a")), "a", null, priced = false, catalogProvider = null)
        m.fallbacks = listOf(FallbackModel("small", 10, provider = fb))
        val e = shouldThrow<IllegalStateException> { runBlocking { m.stream(req("x".repeat(400))).collect() } }
        e.message!! shouldContain "small"
        e.message!! shouldContain "10-token window"
        fb.models shouldBe emptyList()
    }

    test("a billed fallback is metered even when the session's own model is free") {
        val m = MeteredProvider.wrap(FakeProvider(down = setOf("a")), "a", CATALOG, priced = false, catalogProvider = null)
        m.fallbacks = listOf(FallbackModel("cloud", 200_000, provider = FakeProvider(), price = CATALOG.find("cloud", "anthropic")))
        runBlocking { m.complete(req()) }
        m.meter.totalUsd shouldBeGreaterThan 0.9
    }

    test("requests naming another model keep it and never fall back") {
        val p = FakeProvider(down = setOf("escalate"))
        val m = MeteredProvider.wrap(p, "a", null, priced = false, catalogProvider = null)
        m.fallbacks = listOf(FallbackModel("b", 32_000))
        (runBlocking { m.complete(req().copy(model = "escalate")) } is LLMResponse.Error) shouldBe true
        p.models shouldBe listOf("escalate")
    }
})
