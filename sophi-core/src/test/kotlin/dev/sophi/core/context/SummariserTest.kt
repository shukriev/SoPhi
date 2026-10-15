package dev.sophi.core.context

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot

class SummariserTest : FunSpec({
    val provider = mockk<LLMProvider>()
    val ws = WorkingSet(listOf(FileTouch("a/B.kt", 1, 1, "edit")), listOf("TODO"), listOf(CommandRun("mvn -q test", 1)), listOf(ToolError("edit_file", "Error: old string not found")))
    beforeTest { clearMocks(provider) }

    test("structured: LLM sections plus exact WorkingSet sections in spec order, budget 3% of the window") {
        val req = slot<CompletionRequest>()
        coEvery { provider.complete(capture(req)) } returns LLMResponse.Text(
            "## Goal\nFix the build\n## Decisions & findings\nB.kt had a typo\n## Errors & attempts\nedit failed once\n## Open items\nrerun tests", TokenUsage(1, 1))
        val out = Summariser(provider, CompactionMode.STRUCTURED, windowTokens = 32_000).summarise("USER: fix", ws, "m")
        listOf("## Goal", "## Decisions & findings", "## Files touched", "## Commands & outcomes", "## Errors & attempts", "## Open items")
            .map { out.indexOf(it) }.zipWithNext().all { (a, b) -> a in 0 until b } shouldBe true
        out shouldContain "a/B.kt — read 1, written 1, last: edit"
        out shouldContain "mvn -q test → exit 1"
        out shouldContain "edit failed once"
        out shouldContain "edit_file: Error: old string not found"
        req.captured.maxTokens shouldBe 960
        req.captured.reasoningEffort shouldBe "none"
    }

    test("budget is clamped to 400..2000") {
        Summariser(provider, CompactionMode.STRUCTURED, 4_000).budgetTokens shouldBe 400
        Summariser(provider, CompactionMode.STRUCTURED, 200_000).budgetTokens shouldBe 2_000
        Summariser(provider, CompactionMode.GENERIC, 200_000).budgetTokens shouldBe 512
    }

    test("a failed call falls back to the truncated transcript and still carries the exact sections") {
        coEvery { provider.complete(any()) } returns LLMResponse.Error("boom")
        val out = Summariser(provider, CompactionMode.STRUCTURED, 32_000).summarise("USER: fix the build\nASSISTANT: ok", ws, "m")
        out shouldContain "USER: fix the build"
        out shouldContain "## Files touched"
    }

    test("generic keeps today's prompt and 512 tokens") {
        val req = slot<CompletionRequest>()
        coEvery { provider.complete(capture(req)) } returns LLMResponse.Text("s", TokenUsage(1, 1))
        Summariser(provider, CompactionMode.GENERIC, 32_000).summarise("t", ws, "m") shouldBe "s"
        req.captured.messages.first().content shouldContain "Summarise the following steps of an agent's work concisely"
        req.captured.maxTokens shouldBe 512
        req.captured.reasoningEffort shouldBe "none"
    }
})
