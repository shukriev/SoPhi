package dev.sophi.cli

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.dryrun.ChangePlan
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.PlanStatus
import dev.sophi.core.dryrun.PlanStep
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.sdk.RuntimeBuilder
import dev.sophi.sdk.SophiRuntime
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

private class Okta : Tool {
    val calls = mutableListOf<String>()
    override val name = "okta__deactivate_user"
    override val description = "fake"
    override val parametersJson = "{}"
    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
    override fun reach(argumentsJson: String) = Reach.OUTSIDE_CHANGE
    override suspend fun execute(argumentsJson: String): String { calls += argumentsJson; return "deactivated" }
}

class PlansCommandTest : FunSpec({
    fun setup(): Triple<SophiRuntime, Okta, ChangePlan> {
        val okta = Okta()
        val rt = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>(); sessionsDir = tempdir().toPath(); model = "m"
            tool(okta)
            planFirst(PlanFirst.OUTSIDE, dir = tempdir().toPath())
        }.contextWindowTokens(100_000).build()
        val plan = ChangePlan("plan_c", 1_700_000_000_000L, "offboard jane", "s-gone", reads = emptyList(),
            steps = listOf(PlanStep(1, "okta__deactivate_user", """{"id":"00u9"}""", RiskLevel.DESTRUCTIVE, false)))
        rt.planFirst!!.store.save(plan)
        return Triple(rt, okta, plan)
    }

    fun run(rt: SophiRuntime, line: String?): String {
        val out = mutableListOf<String>()
        val words = line?.split(" ").orEmpty()
        runBlocking { plansAction(words.getOrNull(0), words.getOrNull(1), rt, NoEscInput, out::add) }
        return out.joinToString("\n")
    }

    test("list shows status and change count") {
        val (rt, _, _) = setup()
        val text = run(rt, null)
        text shouldContain "plan_c"
        text shouldContain "pending"
        text shouldContain "1 change"
        text shouldContain "offboard jane"
    }

    test("approve runs a pending plan") {
        val (rt, okta, _) = setup()
        val text = run(rt, "approve plan_c")
        okta.calls shouldBe listOf("""{"id":"00u9"}""")
        text shouldContain "✓ 1 okta deactivate_user"
        // what's approved is shown before it runs
        (text.indexOf("1 okta deactivate_user id=00u9") in 0 until text.indexOf("✓ 1")) shouldBe true
        rt.plan("plan_c")!!.status shouldBe PlanStatus.DONE
    }

    test("run refuses a pending plan with the reason") {
        val (rt, okta, _) = setup()
        run(rt, "run plan_c") shouldContain "wasn't approved"
        okta.calls shouldBe emptyList()
    }

    test("show renders the plan and cancel stops it") {
        val (rt, _, _) = setup()
        run(rt, "show plan_c") shouldContain "1 okta deactivate_user id=00u9"
        run(rt, "cancel plan_c") shouldContain "Cancelled"
        rt.plan("plan_c")!!.status shouldBe PlanStatus.CANCELLED
        run(rt, "approve plan_c") shouldContain "cancelled"
    }

    test("unknown id says so") {
        val (rt, _, _) = setup()
        run(rt, "show plan_nope") shouldBe "No plan plan_nope"
    }

    test("/plans show renders the plan") {
        val (rt, _, _) = setup()
        val out = mutableListOf<String>()
        runBlocking { PlanFirstHost(rt, NoEscInput, out::add).command("show", "plan_c") }
        out.joinToString("\n") shouldContain "offboard jane: 1 change in 1 system"
    }
})
