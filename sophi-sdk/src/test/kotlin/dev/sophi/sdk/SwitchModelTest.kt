package dev.sophi.sdk

import dev.sophi.ai.api.BudgetExceededException
import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

private class RecordingProvider : LLMProvider {
    val models = mutableListOf<String>()
    override val name = "rec"
    override suspend fun complete(request: CompletionRequest): LLMResponse {
        models += request.model
        return LLMResponse.Text("ok", usage = TokenUsage(1, 0))
    }
    override fun stream(request: CompletionRequest) = run {
        models += request.model
        flowOf(StreamEvent.Content("ok"), StreamEvent.Usage(TokenUsage(1, 0)))
    }
}

/** Model `a` costs $1 per input token, so one request is $1. */
private val PRICEY = ModelCatalog.parse("""{"anthropic":{"a":{"i":1000000,"p":0}}}""")

class SwitchModelTest : FunSpec({
    fun runtime(p: LLMProvider, configure: RuntimeBuilder.() -> Unit = {}) = RuntimeBuilder().apply {
        provider = p; contextWindowTokens(32_000); model = "a"; sessionsDir = tempdir().toPath(); usageLogPath = null
        configure()
    }.build()

    test("switchModel applies from the next turn") {
        val p = RecordingProvider()
        val rt = runtime(p)
        runBlocking {
            val s = rt.sessionManager.create()
            rt.streamTurn(s, "one") {}
            rt.switchModel("b", 16_000)
            rt.streamTurn(s, "two") {}
        }
        p.models.filter { it in setOf("a", "b") } shouldBe listOf("a", "b")
        rt.config.model shouldBe "b"
        rt.contextWindowTokens shouldBe 16_000
        rt.close()
    }

    test("a request naming another model isn't switched") {
        val p = RecordingProvider()
        val rt = runtime(p)
        rt.switchModel("b", 16_000)
        runBlocking { rt.provider!!.complete(CompletionRequest(listOf(Message(MessageRole.USER, "hi")), model = "escalate")) }
        p.models.last() shouldBe "escalate"
        rt.close()
    }

    test("budget() stops the second turn") {
        val p = RecordingProvider()
        val rt = runtime(p) { modelCatalog = PRICEY; priced = true; catalogProvider = "anthropic"; budget(0.5) }
        runBlocking {
            val s = rt.sessionManager.create()
            rt.streamTurn(s, "one") {}
            val calls = p.models.size
            val after = rt.streamTurn(s, "two") {}
            p.models.size shouldBe calls
            after.branch().last().metadata["stopReason"] shouldBe "BudgetExceeded"
        }
        rt.close()
    }

    test("a request through runtime.provider is metered and refused over budget") {
        val p = RecordingProvider()
        val rt = runtime(p) { modelCatalog = PRICEY; priced = true; budget(0.5) }
        val req = CompletionRequest(listOf(Message(MessageRole.USER, "hi")), model = "a")
        runBlocking { rt.provider!!.complete(req) }
        rt.costMeter.totalUsd shouldBeGreaterThan 0.9
        shouldThrow<BudgetExceededException> { runBlocking { rt.provider!!.complete(req) } }
        rt.close()
    }

    test("a host's own meter under a wrapper counts each request once") {
        val p = RecordingProvider()
        val m = MeteredProvider.wrap(p, "a", PRICEY, priced = true, catalogProvider = null)
        val outer = object : LLMProvider by m {} // stands in for the System-1 wrapper
        val rt = runtime(outer) { metered = m }
        runBlocking { rt.streamTurn(rt.sessionManager.create(), "one") {} }
        (rt.costMeter === m.meter) shouldBe true
        rt.costMeter.totalUsd shouldBe 1.0
        rt.switchModel("b", 16_000)
        m.activeModel shouldBe "b"
        rt.close()
    }
})
