package dev.sophi.sdk

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.ImageRef
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

private class ImageRecordingProvider : LLMProvider {
    val requests = mutableListOf<CompletionRequest>()
    override val name = "rec"
    override suspend fun complete(request: CompletionRequest): LLMResponse = LLMResponse.Text("ok", usage = TokenUsage(1, 0))
    override fun stream(request: CompletionRequest) = run {
        requests += request
        flowOf(StreamEvent.Content("ok"), StreamEvent.Usage(TokenUsage(1, 0)))
    }
    /** Images on this request's user messages, in order. */
    fun imagesOf(i: Int) = requests[i].messages.flatMap { it.images }
}

/** `seer` can see images, `reader` can't. */
private val CATALOG = ModelCatalog.parse("""{"anthropic":{"seer":{"v":1},"reader":{}}}""")

class SophiRuntimeImagesTest : FunSpec({
    val shot = ImageRef("/tmp/sophi-sdk-test.png", "image/png")

    fun runtime(p: LLMProvider, start: String, configure: RuntimeBuilder.() -> Unit = {}) = RuntimeBuilder().apply {
        provider = p; contextWindowTokens(32_000); model = start; sessionsDir = tempdir().toPath(); usageLogPath = null
        modelCatalog = CATALOG
        configure()
    }.build()

    test("vision model from the catalog gets the image") {
        val p = ImageRecordingProvider()
        runBlocking { runtime(p, "seer").let { rt -> rt.streamTurn(rt.sessionManager.create(), "look", images = listOf(shot)) {} } }
        p.imagesOf(0) shouldBe listOf(shot)
    }

    test("model without vision gets none") {
        val p = ImageRecordingProvider()
        runBlocking { runtime(p, "reader").let { rt -> rt.streamTurn(rt.sessionManager.create(), "look", images = listOf(shot)) {} } }
        p.imagesOf(0) shouldBe emptyList()
    }

    test("switchModel to a text-only model drops images from the next request") {
        val p = ImageRecordingProvider()
        runBlocking {
            val rt = runtime(p, "seer")
            val s = rt.streamTurn(rt.sessionManager.create(), "look", images = listOf(shot)) {}
            rt.switchModel("reader", 32_000)
            rt.streamTurn(s, "and now?") {}
        }
        p.imagesOf(0) shouldBe listOf(shot)
        p.imagesOf(1) shouldBe emptyList()
    }

    test("host override of supportsImages wins") {
        val p = ImageRecordingProvider()
        runBlocking { runtime(p, "reader") { supportsImages { true } }.let { rt -> rt.streamTurn(rt.sessionManager.create(), "look", images = listOf(shot)) {} } }
        p.imagesOf(0) shouldBe listOf(shot)
    }
})
