package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.session.AgentSession
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.sdk.Sophi
import dev.sophi.sdk.SophiRuntime
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private class Change(override val name: String = "okta__deactivate_user") : Tool {
    var calls = 0
    override val description = "fake"
    override val parametersJson = "{}"
    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
    override fun reach(argumentsJson: String) = Reach.OUTSIDE_CHANGE
    override suspend fun execute(argumentsJson: String): String { calls++; return "done" }
}

/** Like a real terminal: the control-key reader takes a moment to give the terminal back after it's cancelled. */
private class SlowHandoffInput(lines: List<String>) : InputSource by ScriptedInputSource(lines) {
    @Volatile var keysActive = false
    val activeAtRead = mutableListOf<Boolean>()
    private val lines = ArrayDeque(lines)
    override suspend fun readLine(prefill: String): String? { activeAtRead += keysActive; return lines.removeFirstOrNull() }
    override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit, onKey: (suspend (TurnKey) -> Unit)?) {
        keysActive = true
        try { kotlinx.coroutines.awaitCancellation() } finally { withContext(NonCancellable) { delay(150) }; keysActive = false }
    }
}

class PlanReviewFlowTest : FunSpec({
    fun runtime(change: Change): SophiRuntime {
        val provider = mockk<LLMProvider>()
        every { provider.stream(any()) } returnsMany listOf(
            flowOf(StreamEvent.ToolCallsReady(listOf(ToolCall("c1", change.name, """{"id":"00u9"}""")))),
            flowOf(StreamEvent.Content("I'll deactivate Jane once you approve.")),
        )
        return Sophi.runtime {
            this.provider = provider; model = "m"; contextWindowTokens(100_000); sessionsDir = tempdir().toPath()
            confirmationPolicy(ConfirmationPolicy.ALLOW_ALL)
            tool(change)
            planFirst(PlanFirst.OUTSIDE, dir = tempdir().toPath())
        }
    }

    test("the review reads the terminal only after the turn's key reader let go of it") {
        val change = Change()
        val rt = runtime(change)
        val input = SlowHandoffInput(listOf("y"))
        val tc = TurnController(rt, input, LiveRegion(StringBuilder()) { 80 }, planFirst = PlanFirstHost(rt, input) {}) {}
        runBlocking { tc.runTurn(rt.sessionManager.load(rt.newSession()), "offboard jane") }
        input.activeAtRead shouldBe listOf(false)
        change.calls shouldBe 1
    }

    test("after an approved replay the turn's session carries the outcome, for the next turn") {
        val change = Change()
        val rt = runtime(change)
        val input = ScriptedInputSource(listOf("y"))
        val tc = TurnController(rt, input, LiveRegion(StringBuilder()) { 80 }, planFirst = PlanFirstHost(rt, input) {}) {}
        val after = runBlocking { tc.runTurn(rt.sessionManager.load(rt.newSession()), "offboard jane") }
        after.tip!!.metadata["plan"] shouldBe rt.plans().single().id
        after.tip!!.content shouldContain "1 ok"
    }

    test("a plan that changed state elsewhere is reported, not thrown") {
        val change = Change()
        val rt = runtime(change)
        val out = mutableListOf<String>()
        val input = ScriptedInputSource(listOf("y"))
        runBlocking { rt.streamTurn(rt.sessionManager.load(rt.newSession()), "offboard jane") {} }
        val plan = rt.plans().single()
        rt.cancelPlan(plan.id) // e.g. the companion cancelled it meanwhile
        runBlocking { PlanFirstHost(rt, input, out::add).review(plan, AgentSession(id = "x")) }
        out.joinToString("\n") shouldContain "cancelled"
        change.calls shouldBe 0
    }
})
