package dev.sophi.cli.goal

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GoalTriggerTest : FunSpec({
    test("recordSource maps Explicit to \"explicit\"") {
        GoalTrigger.Explicit.recordSource() shouldBe "explicit"
    }
    test("recordSource maps Autonomous(Rules) to \"rules\"") {
        GoalTrigger.Autonomous(TriggerSource.Rules).recordSource() shouldBe "rules"
    }
    test("recordSource maps Autonomous(Llm) to \"llm\"") {
        GoalTrigger.Autonomous(TriggerSource.Llm).recordSource() shouldBe "llm"
    }
})
