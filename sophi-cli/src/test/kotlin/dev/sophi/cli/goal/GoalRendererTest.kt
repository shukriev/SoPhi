package dev.sophi.cli.goal

import dev.sophi.cli.LiveRegion
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.plan.DecompositionTrigger
import dev.sophi.core.agent.plan.Plan
import dev.sophi.core.agent.plan.PlanProgressEvent
import dev.sophi.core.agent.plan.PlanStep
import dev.sophi.core.agent.plan.StepStatus
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class GoalRendererTest : FunSpec({
    fun plan() = Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "do it")))

    fun renderer(session: AgentSession, output: MutableList<String>) = GoalRenderer(
        session, plan(), LiveRegion(StringBuilder()) { 80 }, { output.add(it) },
        autoExitTokenView = true
    )

    test("StepStarted prints a step header") {
        val output = mutableListOf<String>()
        val r = renderer(AgentSession(id = "s"), output)
        runBlocking { r.handle(PlanProgressEvent.StepStarted("plan_1", plan().steps[0], 1)) }
        output.any { it.contains("step 1/1") && it.contains("do it") } shouldBe true
    }

    test("a first StepAttempt is silent — StepStarted already announced the step") {
        val output = mutableListOf<String>()
        val r = renderer(AgentSession(id = "s"), output)
        runBlocking { r.handle(PlanProgressEvent.StepAttempt("plan_1", plan().steps[0], 1, "m", "child1", attempt = 1)) }
        output.isEmpty() shouldBe true
    }

    test("a second StepAttempt prints a retry line naming the escalation model") {
        val output = mutableListOf<String>()
        val r = renderer(AgentSession(id = "s"), output)
        runBlocking {
            r.handle(PlanProgressEvent.StepAttempt("plan_1", plan().steps[0], 1, "strong-model", "child1", attempt = 2))
        }
        output.any { it.contains("retrying") && it.contains("strong-model") } shouldBe true
    }

    test("Escalating prints the confidence and the model being escalated to") {
        val output = mutableListOf<String>()
        val r = renderer(AgentSession(id = "s"), output)
        runBlocking { r.handle(PlanProgressEvent.Escalating("s1", 0.2, "strong-model")) }
        output.single() shouldContain "escalating to strong-model"
    }

    test("Decomposed reports the sub-plan a step expanded into") {
        val output = mutableListOf<String>()
        val r = renderer(AgentSession(id = "s"), output)
        runBlocking { r.handle(PlanProgressEvent.Decomposed("s1", "plan_2", DecompositionTrigger.Declared)) }
        output.single() shouldContain "plan_2"
    }

    test("StepFinished appends a replay=false entry to the anchor session and updates lastStepOutput") {
        val session = AgentSession(id = "s")
        val output = mutableListOf<String>()
        val r = renderer(session, output)
        runBlocking {
            r.handle(PlanProgressEvent.StepStarted("plan_1", plan().steps[0], 1))
            r.handleTurnEvent(TurnEvent.Token("all done"))
            r.handle(
                PlanProgressEvent.StepFinished("plan_1", plan().steps[0].copy(status = StepStatus.Done, confidence = 0.9), 1)
            )
        }
        r.lastStepOutput shouldBe "all done"
        val entry = session.entries.single()
        entry.role shouldBe EntryRole.ASSISTANT
        entry.metadata["replay"] shouldBe "false"
        entry.metadata["stepId"] shouldBe "s1"
        entry.content shouldContain "all done"
    }

    test("Replanned swaps in the new plan and re-renders its pending steps") {
        val output = mutableListOf<String>()
        val r = renderer(AgentSession(id = "s"), output)
        val newPlan = plan().copy(steps = listOf(PlanStep(id = "s1b", instruction = "retry")), version = 2)
        runBlocking { r.handle(PlanProgressEvent.Replanned(newPlan, "s1", "step s1 failed")) }
        r.currentPlan shouldBe newPlan
        output.any { it.contains("replanning") && it.contains("v2") } shouldBe true
        output.any { it.contains("[s1b] retry") } shouldBe true
    }
})
