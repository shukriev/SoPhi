package dev.sophi.core.context

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import dev.sophi.core.prompt.PromptBuilder
import dev.sophi.core.session.FileSessionManager
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import dev.sophi.ai.api.CompletionRequest

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
        result.branch().first().role shouldBe EntryRole.USER
        result.branch().first().content shouldStartWith "Summary of the conversation so far:\n"
        result.branch().first().content shouldContain "Summary text."
        result.branch().first().metadata["_compacted"] shouldBe "true"
        result.branch()[1].role shouldBe EntryRole.ASSISTANT
        result.branch()[1].content shouldBe "Understood — continuing from that summary."
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

        // 2 summary entries + 2 recent
        result.branch() shouldHaveSize 4
        result.branch()[2].content shouldBe "recent-1"
        result.branch()[3].content shouldBe "recent-2"
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

    test("compact() preserves entries on other branches") {
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(5, 3))

        val s = AgentSession(id = "s-branches")
        val a = s.append(EntryRole.USER, "a")
        val side = s.append(EntryRole.ASSISTANT, "side-answer")
        s.checkout(a.id)
        s.append(EntryRole.ASSISTANT, "main-1")
        s.append(EntryRole.USER, "main-2")
        s.append(EntryRole.ASSISTANT, "main-3")

        val result = compactor.compact(s, config, keepRecentCount = 2)

        result.branch().map { it.content }.drop(1) shouldBe
            listOf("Understood — continuing from that summary.", "main-2", "main-3")
        result.branch().first().content shouldStartWith "Summary of the conversation so far:\n"
        result.checkout(side.id)
        result.branch().map { it.content } shouldBe listOf("a", "side-answer")
    }

    test("compact() keeps the compacted prefix in the entry list, off the active branch") {
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(5, 3))

        val s = session(
            EntryRole.USER to "old-1",
            EntryRole.ASSISTANT to "old-2",
            EntryRole.USER to "recent-1",
            EntryRole.ASSISTANT to "recent-2"
        )
        val result = compactor.compact(s, config, keepRecentCount = 2)

        result.entries.map { it.content }.containsAll(listOf("old-1", "old-2")) shouldBe true
        result.branch().map { it.content }.contains("old-1") shouldBe false
    }

    test("compact() excludes replay=false tool entries from the summary but keeps them in entries") {
        val requestSlot = slot<CompletionRequest>()
        coEvery { provider.complete(capture(requestSlot)) } returns
            LLMResponse.Text("summary", TokenUsage(5, 3))

        val s = AgentSession(id = "s-replay")
        s.append(EntryRole.USER, "please run the tool")
        s.append(EntryRole.ASSISTANT, "", mapOf("replay" to "false", "toolCalls" to "[grep]"))
        s.append(EntryRole.TOOL_RESULT, "SECRET_TOOL_OUTPUT_XYZ", mapOf("replay" to "false"))
        s.append(EntryRole.ASSISTANT, "here is the answer")
        s.append(EntryRole.USER, "recent-1")
        s.append(EntryRole.ASSISTANT, "recent-2")

        val result = compactor.compact(s, config, keepRecentCount = 2)

        // Summary prompt sent to the provider must not contain the raw tool-result text.
        val summaryPrompt = requestSlot.captured.messages.joinToString("\n") { it.content }
        summaryPrompt.contains("SECRET_TOOL_OUTPUT_XYZ") shouldBe false

        // The compacted session still contains the replay=false tool entries.
        result.entries.any { it.content == "SECRET_TOOL_OUTPUT_XYZ" } shouldBe true
    }

    test("compact() preserves parentSessionId on the compacted session") {
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(5, 3))

        val s = AgentSession(id = "s-compact-child", parentSessionId = "parent-1")
        listOf(
            EntryRole.USER to "a",
            EntryRole.ASSISTANT to "b",
            EntryRole.USER to "c",
            EntryRole.ASSISTANT to "d",
            EntryRole.USER to "e"
        ).forEach { (role, content) -> s.append(role, content) }

        val result = compactor.compact(s, config, keepRecentCount = 2)

        result.parentSessionId shouldBe "parent-1"
    }

    // A thinking model (bonsai on LM Studio) spends a 512-token budget reasoning and returns nothing.
    test("compact() asks the summary call for no reasoning") {
        val captured = slot<dev.sophi.ai.api.CompletionRequest>()
        coEvery { provider.complete(capture(captured)) } returns LLMResponse.Text("Summary.", TokenUsage(1, 1))
        val s = session(EntryRole.USER to "a", EntryRole.ASSISTANT to "b", EntryRole.USER to "c", EntryRole.ASSISTANT to "d")
        compactor.compact(s, config, keepRecentCount = 2)
        captured.captured.reasoningEffort shouldBe "none"
        captured.captured.judgment shouldBe dev.sophi.ai.system1.Judgments.COMPACTOR
    }

    test("cross-turn summary is a leading USER/ASSISTANT pair and the first kept entry is a USER entry") {
        coEvery { provider.complete(any()) } returns LLMResponse.Text("## Goal\ng\n## Decisions & findings\nd\n## Errors & attempts\n-\n## Open items\n-", TokenUsage(1, 1))
        val s = session(EntryRole.USER to "u1", EntryRole.ASSISTANT to "a1", EntryRole.USER to "u2", EntryRole.ASSISTANT to "a2",
            EntryRole.USER to "u3", EntryRole.ASSISTANT to "a3")
        val out = compactor.compact(s, config, keepRecentCount = 3)   // a raw cut would start at "a2" - must move back to "u2"
        val b = out.branch()
        b[0].role shouldBe EntryRole.USER; b[0].content shouldStartWith "Summary of the conversation so far:"
        b[1].role shouldBe EntryRole.ASSISTANT
        b[2].content shouldBe "u2"
        assertTemplateSafe(PromptBuilder.build(b))
    }

    test("a compacted session survives save and reload with the same prompt") {
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(1, 1))
        val s = session(EntryRole.USER to "u1", EntryRole.ASSISTANT to "a1", EntryRole.USER to "u2", EntryRole.ASSISTANT to "a2",
            EntryRole.USER to "u3", EntryRole.ASSISTANT to "a3")
        val out = compactor.compact(s, config, keepRecentCount = 3)
        val manager = FileSessionManager(tempdir().toPath())
        manager.save(out)
        val reloaded = manager.load(out.id)!!
        PromptBuilder.build(reloaded.branch()) shouldBe PromptBuilder.build(out.branch())
        reloaded.branch()[0].metadata["_workingSet"] shouldBe out.branch()[0].metadata["_workingSet"]
        (reloaded.branch()[0].metadata["_workingSet"] != null) shouldBe true
    }

    test("no USER entry in the kept tail: the session is returned unchanged") {
        val s = session(EntryRole.USER to "u1", EntryRole.ASSISTANT to "a1", EntryRole.ASSISTANT to "a2", EntryRole.ASSISTANT to "a3")
        compactor.compact(s, config, keepRecentCount = 2) shouldBe s
        coVerify(exactly = 0) { provider.complete(any()) }
    }

    test("needsCompaction: by replayed size against half the window") {
        val big = session(EntryRole.USER to "x".repeat(70_000), EntryRole.ASSISTANT to "y", EntryRole.USER to "u2", EntryRole.ASSISTANT to "a2",
            EntryRole.USER to "u3", EntryRole.ASSISTANT to "a3")
        compactor.needsCompaction(big, config, windowTokens = 32_000) shouldBe true
        compactor.needsCompaction(session(EntryRole.USER to "hi"), config, 32_000) shouldBe false
    }

    test("needsCompaction: by replayed entry count") {
        val s = session(EntryRole.USER to "u1", EntryRole.ASSISTANT to "a1", EntryRole.USER to "u2", EntryRole.ASSISTANT to "a2",
            EntryRole.USER to "u3", EntryRole.ASSISTANT to "a3")
        compactor.needsCompaction(s, config.copy(maxBranchLength = 5), 32_000) shouldBe true
        compactor.needsCompaction(s, config.copy(maxBranchLength = 6), 32_000) shouldBe false
    }

    test("needsCompaction ignores replay=false entries, and is false when compact would be a no-op") {
        val s = session(EntryRole.USER to "u1", EntryRole.ASSISTANT to "a1", EntryRole.USER to "u2", EntryRole.ASSISTANT to "a2",
            EntryRole.USER to "u3")
        s.append(EntryRole.TOOL_RESULT, "z".repeat(200_000), mapOf("replay" to "false"))
        compactor.needsCompaction(s, config, 32_000) shouldBe false
        // over the size bar but nothing before the kept tail to summarise
        val short = session(EntryRole.USER to "x".repeat(70_000), EntryRole.ASSISTANT to "y")
        compactor.needsCompaction(short, config, 32_000) shouldBe false
    }

    test("a second cross-turn compaction keeps files and commands touched only before the first") {
        coEvery { provider.complete(any()) } returns LLMResponse.Text("s", TokenUsage(1, 1))
        val s = AgentSession(id = "s-ws")
        s.append(EntryRole.USER, "u1")
        s.append(EntryRole.ASSISTANT, "", mapOf("replay" to "false", "toolCalls" to
            """[{"id":"1","name":"read_file","argumentsJson":"{\"path\":\"Old.kt\"}"},{"id":"2","name":"bash","argumentsJson":"{\"command\":\"mvn old\"}"}]"""))
        s.append(EntryRole.TOOL_RESULT, "x", mapOf("replay" to "false", "toolCallId" to "1", "toolName" to "read_file"))
        s.append(EntryRole.TOOL_RESULT, "y", mapOf("replay" to "false", "toolCallId" to "2", "toolName" to "bash"))
        listOf("a1" to "u2", "a2" to "u3", "a3" to "u4", "a4" to "u5").forEach { (a, u) ->
            s.append(EntryRole.ASSISTANT, a); s.append(EntryRole.USER, u)
        }
        s.append(EntryRole.ASSISTANT, "a5")
        val first = compactor.compact(s, config, keepRecentCount = 6)
        first.branch()[0].content shouldContain "Old.kt"
        first.append(EntryRole.USER, "u6"); first.append(EntryRole.ASSISTANT, "a6")
        first.append(EntryRole.USER, "u7"); first.append(EntryRole.ASSISTANT, "a7")
        val second = compactor.compact(first, config, keepRecentCount = 4)
        second.branch()[0].content shouldContain "Old.kt"
        second.branch()[0].content shouldContain "mvn old → exit 0"
    }

    test("images count toward compaction and show as [image]") {
        val s = AgentSession(id = "s-img")
        val meta = dev.sophi.core.session.imagesMetadata(List(4) { dev.sophi.ai.api.ImageRef("/tmp/x$it.png") })
        repeat(6) { s.append(EntryRole.USER, "q$it", meta); s.append(EntryRole.ASSISTANT, "a$it") }
        // 12 short entries: text alone is far below half of a 40,000-token window; 24 images at 1,500 each are not.
        ContextCompactor(provider).needsCompaction(s, config.copy(maxBranchLength = 1000), 40_000) shouldBe true

        val prompt = slot<dev.sophi.ai.api.CompletionRequest>()
        coEvery { provider.complete(capture(prompt)) } returns dev.sophi.ai.api.LLMResponse.Text("sum", dev.sophi.ai.api.TokenUsage(1, 1))
        ContextCompactor(provider).compact(s, config)
        prompt.captured.messages.last().content shouldContain "USER: q0 [image] [image] [image] [image]"
    }
})
