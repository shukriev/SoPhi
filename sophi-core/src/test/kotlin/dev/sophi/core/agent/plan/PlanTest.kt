package dev.sophi.core.agent.plan

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class PlanTest : FunSpec({
    val json = Json { ignoreUnknownKeys = true }

    test("PlanStep defaults to Pending status with no dependencies or confidence") {
        val step = PlanStep(id = "s1", instruction = "do the thing")
        step.status shouldBe StepStatus.Pending
        step.dependsOn shouldBe emptyList()
        step.confidence shouldBe null
        step.modelOverride shouldBe null
    }

    test("Plan round-trips through JSON serialization, including nested steps") {
        val plan = Plan(
            id = "plan_1", goalPrompt = "ship it",
            steps = listOf(
                PlanStep(id = "s1", instruction = "step one", status = StepStatus.Done, confidence = 0.9),
                PlanStep(id = "s2", instruction = "step two", dependsOn = listOf("s1"))
            ),
            version = 2, parentPlanId = "plan_0"
        )
        val encoded = json.encodeToString(Plan.serializer(), plan)
        val decoded = json.decodeFromString(Plan.serializer(), encoded)
        decoded shouldBe plan
    }

    test("StopCondition.ShellCheck round-trips through JSON serialization") {
        val condition: StopCondition = StopCondition.ShellCheck("./run.sh", expectExitZero = false)
        val encoded = json.encodeToString(StopCondition.serializer(), condition)
        val decoded = json.decodeFromString(StopCondition.serializer(), encoded)
        decoded shouldBe condition
    }

    test("StopCondition.LlmJudged round-trips through JSON serialization") {
        val condition: StopCondition = StopCondition.LlmJudged
        val encoded = json.encodeToString(StopCondition.serializer(), condition)
        val decoded = json.decodeFromString(StopCondition.serializer(), encoded)
        decoded shouldBe condition
    }

    test("new tree fields default so old serialized plans still decode") {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val oldLine = """{"id":"plan_1","goalPrompt":"goal","steps":[{"id":"s1","instruction":"do it"}],"version":1}"""
        val plan = json.decodeFromString(Plan.serializer(), oldLine)
        plan.parentStepId shouldBe null
        plan.depth shouldBe 0
        plan.steps.single().decompose shouldBe false
        plan.steps.single().childPlanId shouldBe null
    }

    test("a decomposed plan round-trips its tree fields through serialization") {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val child = Plan(
            id = "plan_2", goalPrompt = "sub goal", depth = 1, parentStepId = "s1",
            steps = listOf(PlanStep(id = "c1", instruction = "sub step", decompose = true, childPlanId = "plan_3"))
        )
        val decoded = json.decodeFromString(Plan.serializer(), json.encodeToString(Plan.serializer(), child))
        decoded shouldBe child
    }

    test("PlanOutcome carries decomposition events and the root plan id") {
        val outcome = PlanOutcome(
            finalStatus = PlanFinalStatus.Met, finalOutput = "done", planVersionCount = 1,
            totalSteps = 2, replans = emptyList(),
            decompositions = listOf(
                DecompositionEvent("s1", "plan_2", 3, PlanFinalStatus.Met, DecompositionTrigger.Declared)
            ),
            planId = "plan_1"
        )
        outcome.decompositions.single().trigger shouldBe DecompositionTrigger.Declared
        outcome.planId shouldBe "plan_1"
        outcome.finalSteps shouldBe emptyList()
    }
})
