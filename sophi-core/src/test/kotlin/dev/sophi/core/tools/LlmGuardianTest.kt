package dev.sophi.core.tools

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.permissions.Effect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class LlmGuardianTest : FunSpec({
    fun replying(text: String): LLMProvider = mockk<LLMProvider>().also {
        coEvery { it.complete(any()) } returns LLMResponse.Text(text, TokenUsage(1, 1))
    }
    suspend fun LlmGuardian.go() = review("bash", "runs a command", """{"command":"rm -rf build"}""", "clean the build")

    test("DENY with a reason") {
        LlmGuardian(replying("DENY: wipes the repo"), "m").go() shouldBe GuardianVerdict(Effect.DENY, "wipes the repo")
    }

    test("a lowercase ask inside an ALLOW reason stays ALLOW") {
        LlmGuardian(replying("ALLOW - no need to ask, it is scratch"), "m").go().effect shouldBe Effect.ALLOW
    }

    test("a bare ASK") {
        LlmGuardian(replying("ASK"), "m").go().effect shouldBe Effect.ASK
    }

    test("no verdict word is ASK") {
        val v = LlmGuardian(replying("I am not sure"), "m").go()
        v.effect shouldBe Effect.ASK
        v.reason shouldContain "no verdict"
    }

    test("a failing provider is ASK") {
        val p = mockk<LLMProvider>()
        coEvery { p.complete(any()) } throws RuntimeException("connection refused")
        val v = LlmGuardian(p, "m").go()
        v.effect shouldBe Effect.ASK
        v.reason shouldStartWith "guardian unavailable"
    }

    test("a timeout is ASK") {
        val p = mockk<LLMProvider>()
        coEvery { p.complete(any()) } coAnswers { delay(5_000); LLMResponse.Text("ALLOW", TokenUsage(1, 1)) }
        LlmGuardian(p, "m", timeout = 50.milliseconds).go().effect shouldBe Effect.ASK
    }

    test("the request names the model, is deterministic, is no System 1 judgment, and carries the call") {
        var seen: CompletionRequest? = null
        val p = mockk<LLMProvider>()
        coEvery { p.complete(any()) } answers { seen = firstArg(); LLMResponse.Text("ALLOW", TokenUsage(1, 1)) }
        LlmGuardian(p, "m").go()
        val r = seen!!
        r.model shouldBe "m"
        r.temperature shouldBe 0.0
        r.judgment shouldBe null
        val user = r.messages.single { it.role == MessageRole.USER }.content
        user shouldContain "clean the build"
        user shouldContain "bash"
        user shouldContain "rm -rf build"
    }
})
