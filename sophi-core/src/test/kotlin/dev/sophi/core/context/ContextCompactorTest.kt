package dev.sophi.core.context

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class ContextCompactorTest : FunSpec({
    val provider = mockk<LLMProvider>()
    val config = AgentConfig(model = "test-model")
    lateinit var compactor: ContextCompactor

    beforeTest {
        clearMocks(provider)
        compactor = ContextCompactor(provider)
    }

    fun session(vararg pairs: Pair<EntryRole, String>): AgentSession {
        val s = AgentSession(id = "s-compact")
        pairs.forEach { (role, content) -> s.append(role, content) }
        return s
    }

    test("compact() returns same session unchanged when entries <= keepRecentCount") {
        val s = session(
            EntryRole.USER to "hi",
            EntryRole.ASSISTANT to "hello"
        )
        val result = compactor.compact(s, config, keepRecentCount = 4)
        result shouldBe s
        coVerify(exactly = 0) { provider.complete(any()) }
    }

    test("compact() calls LLM to summarise when entries > keepRecentCount") {
        coEvery { provider.complete(any()) } returns LLMResponse.Text("Summary text.", TokenUsage(10, 5))

        val s = session(
            EntryRole.USER to "msg1",
            EntryRole.ASSISTANT to "reply1",
            EntryRole.USER to "msg2",
            EntryRole.ASSISTANT to "reply2",
            EntryRole.USER to "msg3",
            EntryRole.ASSISTANT to "reply3"
        )
        val result = compactor.compact(s, config, keepRecentCount = 2)

        coVerify(exactly = 1) { provider.complete(any()) }
        result.branch().first().role shouldBe EntryRole.SYSTEM
        result.branch().first().content shouldBe "Previous conversation summary:\nSummary text."
        result.branch().first().metadata["_compacted"] shouldBe "true"
    }

    test("compact() preserves the most recent keepRecentCount entries after the summary") {
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(5, 3))

        val s = session(
            EntryRole.USER to "old-1",
            EntryRole.ASSISTANT to "old-2",
            EntryRole.USER to "recent-1",
            EntryRole.ASSISTANT to "recent-2"
        )
        val result = compactor.compact(s, config, keepRecentCount = 2)

        // 1 summary entry + 2 recent
        result.branch() shouldHaveSize 3
        result.branch()[1].content shouldBe "recent-1"
        result.branch()[2].content shouldBe "recent-2"
    }

    test("compact() returns new AgentSession with same id") {
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(5, 3))

        val s = session(
            EntryRole.USER to "a",
            EntryRole.ASSISTANT to "b",
            EntryRole.USER to "c",
            EntryRole.ASSISTANT to "d",
            EntryRole.USER to "e"
        )
        val result = compactor.compact(s, config, keepRecentCount = 2)

        result.id shouldBe s.id
    }

    test("compact() does not call LLM when entries exactly equal keepRecentCount") {
        val s = session(
            EntryRole.USER to "a",
            EntryRole.ASSISTANT to "b"
        )
        compactor.compact(s, config, keepRecentCount = 2)
        coVerify(exactly = 0) { provider.complete(any()) }
    }
})
