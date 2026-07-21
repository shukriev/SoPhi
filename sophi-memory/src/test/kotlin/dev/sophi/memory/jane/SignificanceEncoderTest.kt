package dev.sophi.memory.jane

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.memory.TurnObservation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot

class SignificanceEncoderTest : FunSpec({
    val cfg = JanesPalaceConfig(sessionModel = "test-model")
    val turn = TurnObservation("s1", "my daughter Emma starts school Monday", "Noted!", 1_000L)

    test("parses a well-formed verdict") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text(
            """{"memories":[{"text":"User's daughter Emma starts school Monday","room":"ENTITIES",
               "emph":0.2,"aff":0.6,"sensitivity":"PERSONAL","provenance":"USER_DIRECT",
               "causedBy":[],"thread":null,"supersedes":null}],
               "profile":[{"path":"family.daughter.name","value":"Emma"}]}""",
            TokenUsage(1, 1))
        val verdict = SignificanceEncoder(provider, cfg).encode(turn, emptyList())!!
        verdict.memories.single().room shouldBe "ENTITIES"
        verdict.profile.single().path shouldBe "family.daughter.name"
    }

    test("retries once with a stricter instruction when the first response is prose") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("Sure! Here's my analysis…", TokenUsage(1, 1)),
            LLMResponse.Text("""{"memories":[],"profile":[]}""", TokenUsage(1, 1)))
        SignificanceEncoder(provider, cfg).encode(turn, emptyList())!!.memories shouldBe emptyList()
    }

    test("returns null on provider error — encoding is best-effort") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } throws RuntimeException("down")
        SignificanceEncoder(provider, cfg).encode(turn, emptyList()) shouldBe null
    }

    test("prompt carries the turn, recent memory ids, and the exclusion rules") {
        val provider = mockk<LLMProvider>()
        val captured = slot<CompletionRequest>()
        coEvery { provider.complete(capture(captured)) } returns
            LLMResponse.Text("""{"memories":[],"profile":[]}""", TokenUsage(1, 1))
        val recent = listOf(Memory("mem_9", "old fact", Room.KNOWLEDGE, 0.5,
            SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0), Sensitivity.PERSONAL,
            Provenance.USER_DIRECT, 1L, 1L, "s"))
        SignificanceEncoder(provider, cfg).encode(turn, recent)
        val prompt = captured.captured.messages.single().content
        prompt shouldContain "Emma starts school"
        prompt shouldContain "[mem_9]"
        prompt shouldContain "credentials"
    }

    test("requests reasoningEffort=none — structured extraction, not chain-of-thought") {
        val provider = mockk<LLMProvider>()
        val captured = slot<CompletionRequest>()
        coEvery { provider.complete(capture(captured)) } returns
            LLMResponse.Text("""{"memories":[],"profile":[]}""", TokenUsage(1, 1))
        SignificanceEncoder(provider, cfg).encode(turn, emptyList())
        captured.captured.reasoningEffort shouldBe "none"
    }

    test("parses a bare [] the same as an empty {memories:[],profile:[]} verdict") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("[]", TokenUsage(1, 1))
        val verdict = SignificanceEncoder(provider, cfg).encode(turn, emptyList())!!
        verdict.memories shouldBe emptyList()
        verdict.profile shouldBe emptyList()
    }

    test("warns when the provider errors on the first call") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } throws RuntimeException("down")
        val warnings = mutableListOf<String>()
        SignificanceEncoder(provider, cfg, onWarning = { warnings.add(it) }).encode(turn, emptyList())
        warnings.single() shouldContain "encoder call failed"
    }

    test("warns when the retry attempt also errors") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns
            LLMResponse.Text("prose, no json here", TokenUsage(1, 1)) andThenThrows
            RuntimeException("still down")
        val warnings = mutableListOf<String>()
        SignificanceEncoder(provider, cfg, onWarning = { warnings.add(it) }).encode(turn, emptyList())
        warnings.single() shouldContain "encoder call failed on retry"
    }

    test("warns when both attempts return output that doesn't match the expected schema") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("prose, no json here", TokenUsage(1, 1)),
            LLMResponse.Text("still prose, no json here", TokenUsage(1, 1)))
        val warnings = mutableListOf<String>()
        SignificanceEncoder(provider, cfg, onWarning = { warnings.add(it) }).encode(turn, emptyList())
        warnings.single() shouldContain "didn't match the expected schema"
    }
})
