package dev.sophi.core.agent.plan

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class PlanLogTest : FunSpec({
    test("append then versions round-trips a single plan version") {
        val log = PlanLog(tempdir().toPath())
        val plan = Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "do it")))
        log.append(plan)
        log.versions("plan_1") shouldBe listOf(plan)
    }

    test("appending multiple versions of the same plan accumulates, in order") {
        val log = PlanLog(tempdir().toPath())
        val v1 = Plan(id = "plan_1", goalPrompt = "goal", steps = listOf(PlanStep(id = "s1", instruction = "do it")))
        val v2 = v1.copy(version = 2, parentPlanId = "plan_1",
            steps = listOf(PlanStep(id = "s1", instruction = "do it, differently")))
        log.append(v1)
        log.append(v2)
        val versions = log.versions("plan_1")
        versions shouldHaveSize 2
        versions[0].version shouldBe 1
        versions[1].version shouldBe 2
    }

    test("versions returns an empty list for an unknown plan id") {
        val log = PlanLog(tempdir().toPath())
        log.versions("no-such-plan") shouldBe emptyList()
    }
})
