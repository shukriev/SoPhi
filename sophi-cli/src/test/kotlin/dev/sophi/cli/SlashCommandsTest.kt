package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import dev.sophi.core.session.SessionMeta
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class SlashCommandsTest : FunSpec({
    val sessionManager = mockk<SessionManager>(relaxed = true)
    val config = AgentConfig(model = "test-model")
    val output = mutableListOf<String>()

    beforeTest {
        clearMocks(sessionManager)
        output.clear()
    }

    fun makeSession(pairs: Int = 0): AgentSession {
        val s = AgentSession(id = "s1")
        repeat(pairs) { i ->
            s.append(EntryRole.USER, "msg $i")
            s.append(EntryRole.ASSISTANT, "resp $i")
        }
        return s
    }

    test("/list outputs one line per session") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        every { sessionManager.list() } returns listOf(
            SessionMeta("sess-1", 4, 1000L),
            SessionMeta("sess-2", 8, 2000L)
        )
        handler.handle("/list", AgentSession(id = "x"))
        output shouldBe listOf("sess-1  4 entries", "sess-2  8 entries")
    }

    test("/list shows empty message when no sessions") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        every { sessionManager.list() } returns emptyList()
        handler.handle("/list", AgentSession(id = "x"))
        output shouldBe listOf("No saved sessions.")
    }

    test("/branch shows numbered entries with role and content preview") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        val session = makeSession(pairs = 1)
        handler.handle("/branch", session)
        output shouldHaveSize 2
        output[0].contains("USER") shouldBe true
        output[1].contains("ASSISTANT") shouldBe true
    }

    test("/branch shows empty message for empty session") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/branch", AgentSession(id = "s1"))
        output shouldBe listOf("(empty)")
    }

    test("/checkout switches session tip to the given entry id") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        val session = makeSession(pairs = 2)
        val targetId = session.branch().first().id
        handler.handle("/checkout $targetId", session)
        session.tip?.id shouldBe targetId
        output shouldHaveSize 1
        output[0] shouldBe "Checked out entry $targetId"
    }

    test("/checkout with no argument shows usage message") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/checkout", AgentSession(id = "s1"))
        output shouldBe listOf("Usage: /checkout <entry-id>")
    }

    test("/checkout with invalid id outputs error without throwing") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/checkout nonexistent-id", AgentSession(id = "s1"))
        output shouldHaveSize 1
        output[0].startsWith("Error:") shouldBe true
    }

    test("/compact compacts session, saves it, and reports entry count") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("summary", TokenUsage(0, 0))
        val compactor = ContextCompactor(provider)
        val handler = SlashHandler(sessionManager, compactor, config) { output.add(it) }
        val session = makeSession(pairs = 4)  // 8 entries — more than keepRecentCount=4
        handler.handle("/compact", session)
        verify { sessionManager.save(any()) }
        output shouldHaveSize 1
        output[0].startsWith("Compacted to") shouldBe true
    }

    test("/compact without compactor configured shows error") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/compact", AgentSession(id = "s1"))
        output shouldBe listOf("No compactor configured.")
    }

    test("unknown command shows available commands") {
        val handler = SlashHandler(sessionManager, null, config) { output.add(it) }
        handler.handle("/banana", AgentSession(id = "s1"))
        output shouldHaveSize 1
        output[0].contains("Unknown command") shouldBe true
        output[0].contains("/list") shouldBe true
    }
})
