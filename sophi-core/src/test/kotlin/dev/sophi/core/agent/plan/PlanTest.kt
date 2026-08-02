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
})
