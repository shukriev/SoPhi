package dev.sophi.cli.goal

import dev.sophi.cli.LiveRegion
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.plan.Plan
import dev.sophi.core.agent.plan.PlanEvent
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.agent.plan.PlanStep
import dev.sophi.core.agent.plan.ReplanEvent
import dev.sophi.core.agent.plan.StepStatus
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class GoalRendererTest : FunSpec({
    fun plan() = Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "do it")))

    fun renderer(session: AgentSession, output: MutableList<String>) = GoalRenderer(
        session, plan(), LiveRegion(StringBuilder()) { 80 }, { output.add(it) },
        PlanLog(tempdir().toPath()), tokenViewKey = 'T', autoExitTokenView = true
    )

    test("StepAttempt (first attempt) prints a step header") {
        val output = mutableListOf<String>()
        val r = renderer(AgentSession(id = "s"), output)
        runBlocking { r.handle(PlanEvent.StepAttempt(plan().steps[0], 1, "m", "child1", attempt = 1)) }
        output.any { it.contains("step 1/1") && it.contains("do it") } shouldBe true
    }

    test("StepAttempt (second attempt) prints a retry line") {
        val output = mutableListOf<String>()
        val r = renderer(AgentSession(id = "s"), output)
        runBlocking { r.handle(PlanEvent.StepAttempt(plan().steps[0], 1, "strong-model", "child1", attempt = 2)) }
        output.any { it.contains("retrying") && it.contains("strong-model") } shouldBe true
    }

    test("StepFinished appends a replay=false entry to the anchor session and updates lastStepOutput") {
        val session = AgentSession(id = "s")
        val output = mutableListOf<String>()
        val r = renderer(session, output)
        runBlocking {
            r.handle(PlanEvent.StepAttempt(plan().steps[0], 1, "m", "child1", attempt = 1))
            r.handle(PlanEvent.StepTurn("s1", TurnEvent.Token("all done")))
            r.handle(PlanEvent.StepFinished(plan().steps[0].copy(status = StepStatus.Done, confidence = 0.9), 1))
        }
        r.lastStepOutput shouldBe "all done"
        val entry = session.entries.single()
        entry.role shouldBe EntryRole.ASSISTANT
        entry.metadata["replay"] shouldBe "false"
        entry.metadata["stepId"] shouldBe "s1"
        entry.content shouldContain "all done"
    }

    test("Replanned updates currentPlan and appends to PlanLog") {
        val output = mutableListOf<String>()
        val planLog = PlanLog(tempdir().toPath())
        val r = GoalRenderer(AgentSession(id = "s"), plan(), LiveRegion(StringBuilder()) { 80 }, { output.add(it) },
            planLog, tokenViewKey = 'T', autoExitTokenView = true)
        val newPlan = plan().copy(steps = listOf(PlanStep(id = "s1b", instruction = "retry")), version = 2)
        runBlocking { r.handle(PlanEvent.Replanned(ReplanEvent("s1", "step s1 failed", 2), newPlan)) }
        r.currentPlan shouldBe newPlan
        planLog.versions("plan_1") shouldBe listOf(newPlan)
        output.any { it.contains("replanning") && it.contains("v2") } shouldBe true
    }
})
