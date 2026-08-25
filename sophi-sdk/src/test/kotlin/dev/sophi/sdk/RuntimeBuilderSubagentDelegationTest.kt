package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.SessionIdContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.io.path.writeText

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderSubagentDelegationTest : FunSpec({
    test("subagentDelegation() registers delegate_to_subagent, which attributes the created subagent session to the calling turn's session") {
        val agentsDir = tempdir().toPath()
        agentsDir.resolve("explorer.md").writeText("---\nname: explorer\ndescription: reads things\n---\nYou explore.")
        val provider = mockk<LLMProvider>()
        // 1st stream(): the outer turn decides to delegate. 2nd: the nested subagent loop's own
        // turn. 3rd: the outer loop's follow-up once the tool result (the nested reply) comes back.
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(
                ToolCall("c1", "delegate_to_subagent", """{"subagent_type":"explorer","prompt":"go"}""")
            ))),
            flowOf(StreamEvent.Content("nested reply")),
            flowOf(StreamEvent.Content("outer reply"))
        )
        val runtime = RuntimeBuilder().apply {
            this.provider = provider
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).agentsDir(agentsDir).subagentDelegation().build()

        runtime.toolNames() shouldContain "delegate_to_subagent"

        val sessionId = runBlocking { runtime.newSession() }
        // Wrapping the turn in SessionIdContext here mirrors exactly what every real host does
        // (CompanionRuntime.sendMessage, sophi-cli's TurnController, ScheduleEngine.runTask, all
        // fixed earlier in this plan) — this is what proves the wiring, not just the mechanism.
        val reply = runBlocking(SessionIdContext(sessionId)) { runtime.turn(sessionId, "please delegate") }

        reply shouldBe "outer reply"
        val subSessions = runtime.sessionManager.list().filter { it.parentSessionId == sessionId }
        subSessions shouldHaveSize 1
    }

    test("subagentDelegation() without agentsDir() is a no-op") {
        val provider = mockk<LLMProvider>()
        val runtime = RuntimeBuilder().apply {
            this.provider = provider
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).subagentDelegation().build()

        runtime.toolNames() shouldNotContain "delegate_to_subagent"
    }

    test("agentsDir() without subagentDelegation() does not register the tool") {
        val agentsDir = tempdir().toPath()
        agentsDir.resolve("explorer.md").writeText("---\nname: explorer\ndescription: reads things\n---\nYou explore.")
        val provider = mockk<LLMProvider>()
        val runtime = RuntimeBuilder().apply {
            this.provider = provider
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).agentsDir(agentsDir).build()

        runtime.toolNames() shouldNotContain "delegate_to_subagent"
    }
})
