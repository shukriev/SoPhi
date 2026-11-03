package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.core.session.AgentSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

private const val ONE_STEP_PLAN = """{"steps":[{"id":"step_1","instruction":"do x"}]}"""

class GoalRunTest : FunSpec({
    fun runtime(llm: LLMProvider) = RuntimeBuilder().apply {
        provider = llm; sessionsDir = tempdir().toPath(); model = "m"
    }.contextWindowTokens(100_000).build()

    /** The plan, then the critic's confidence, then the judge's verdict; every step replies [stepReply]. */
    fun provider(stepReply: kotlinx.coroutines.flow.Flow<StreamEvent> = flowOf(StreamEvent.Content("x done"))): LLMProvider =
        mockk<LLMProvider>().also {
            coEvery { it.complete(any()) } returnsMany listOf(ONE_STEP_PLAN, "1.0", "YES").map { r -> LLMResponse.Text(r, TokenUsage(1, 1)) }
            every { it.stream(any()) } returns stepReply
        }

    test("a met goal appends the summary and reports Met") {
        val s = AgentSession(id = "s1")
        val result = runtime(provider()).runGoal(s, "do x")
        result.status shouldBe GoalStatus.Met
        result.reply shouldBe "x done"
        s.branch().last().content shouldStartWith "[goal: Met"
    }

    test("approve false declines without touching the session") {
        val s = AgentSession(id = "s1")
        runtime(provider()).runGoal(s, "do x", approve = { false }).status shouldBe GoalStatus.Declined
        s.entries.size shouldBe 0
    }

    test("planner failure is Failed") {
        // A dead server: the plan call, and every call after it, throws.
        val result = runtime(ScriptedProvider()).runGoal(AgentSession(id = "s1"), "do x")
        result.status shouldBe GoalStatus.Failed
        result.error shouldNotBe null
    }

    test("cancel mid-run records the interruption") {
        val s = AgentSession(id = "s1")
        val stepRunning = CompletableDeferred<Unit>()
        val rt = runtime(provider(flow { stepRunning.complete(Unit); awaitCancellation() }))
        val job = async { rt.runGoal(s, "do x") }
        stepRunning.await()
        job.cancel()
        runCatching { job.await() }
        s.branch().last().content shouldStartWith "[goal interrupted]"
    }
})
