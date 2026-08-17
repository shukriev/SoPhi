package dev.sophi.core.agent.plan

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/** A Planner that always returns a one-step plan tagged with [tag], counting its own calls. */
private class TaggedPlanner(private val tag: String) : Planner {
    val planCalls = AtomicInteger(0)
    val replanCalls = AtomicInteger(0)

    override suspend fun plan(goalPrompt: String, context: List<String>): Plan {
        planCalls.incrementAndGet()
        return Plan(
            id = "plan_$tag", goalPrompt = goalPrompt,
            steps = listOf(PlanStep(id = "s_$tag", instruction = "plan $tag"))
        )
    }

    override suspend fun replan(
        current: Plan, anchorStepId: String, reason: String, context: List<String>
    ): Plan {
        replanCalls.incrementAndGet()
        return Plan(
            id = current.id, goalPrompt = current.goalPrompt,
            steps = listOf(PlanStep(id = "s_$tag", instruction = "tail $tag")),
            version = current.version + 1, parentPlanId = current.id
        )
    }
}

class TreePlannerTest : FunSpec({
    val current = Plan(
        id = "plan_1", goalPrompt = "ship the release",
        steps = listOf(PlanStep(id = "s1", instruction = "build", status = StepStatus.Failed))
    )

    test("replan returns the highest-scored candidate tail") {
        val a = TaggedPlanner("a")
        val b = TaggedPlanner("b")
        val c = TaggedPlanner("c")
        val scores = mapOf("tail a" to 0.2, "tail b" to 0.9, "tail c" to 0.5)
        val critic = PlanCritic { _, candidate, _ -> scores.getValue(candidate.steps.single().instruction) }

        val planner = TreePlanner(listOf(a, b, c), critic)
        val result = runBlocking { planner.replan(current, "s1", "step s1 failed") }

        result.steps.single().instruction shouldBe "tail b"
    }

    test("replan asks every delegate exactly once") {
        val a = TaggedPlanner("a")
        val b = TaggedPlanner("b")
        val critic = PlanCritic { _, _, _ -> 0.5 }

        val planner = TreePlanner(listOf(a, b), critic)
        runBlocking { planner.replan(current, "s1", "step s1 failed") }

        a.replanCalls.get() shouldBe 1
        b.replanCalls.get() shouldBe 1
    }

    test("a tie resolves to the first candidate, deterministically") {
        val a = TaggedPlanner("a")
        val b = TaggedPlanner("b")
        val critic = PlanCritic { _, _, _ -> 1.0 }

        val planner = TreePlanner(listOf(a, b), critic)
        repeat(5) {
            val result = runBlocking { planner.replan(current, "s1", "reason") }
            result.steps.single().instruction shouldBe "tail a"
        }
    }

    test("an all-fail-open critic reproduces the old width-1 behavior") {
        val a = TaggedPlanner("a")
        val b = TaggedPlanner("b")
        val c = TaggedPlanner("c")
        // 1.0 is LlmPlanCritic's fail-open score.
        val critic = PlanCritic { _, _, _ -> 1.0 }

        val planner = TreePlanner(listOf(a, b, c), critic)
        val result = runBlocking { planner.replan(current, "s1", "reason") }

        result.steps.single().instruction shouldBe "tail a"
    }

    test("a single delegate short-circuits: no critic call, no extra planner call") {
        val a = TaggedPlanner("a")
        val criticCalls = AtomicInteger(0)
        val critic = PlanCritic { _, _, _ -> criticCalls.incrementAndGet(); 1.0 }

        val planner = TreePlanner(listOf(a), critic)
        val result = runBlocking { planner.replan(current, "s1", "reason") }

        result.steps.single().instruction shouldBe "tail a"
        a.replanCalls.get() shouldBe 1
        criticCalls.get() shouldBe 0
    }

    test("plan delegates to the first planner only and never scores") {
        val a = TaggedPlanner("a")
        val b = TaggedPlanner("b")
        val criticCalls = AtomicInteger(0)
        val critic = PlanCritic { _, _, _ -> criticCalls.incrementAndGet(); 1.0 }

        val planner = TreePlanner(listOf(a, b), critic)
        val result = runBlocking { planner.plan("ship the release") }

        result.steps.single().instruction shouldBe "plan a"
        a.planCalls.get() shouldBe 1
        b.planCalls.get() shouldBe 0
        criticCalls.get() shouldBe 0
    }

    test("an empty delegate list is rejected at construction") {
        val critic = PlanCritic { _, _, _ -> 1.0 }
        try {
            TreePlanner(emptyList(), critic)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            (e.message ?: "").contains("at least one") shouldBe true
        }
    }

    test("the critic receives the goal prompt and the failure reason") {
        val a = TaggedPlanner("a")
        val b = TaggedPlanner("b")
        val seen = mutableListOf<Pair<String, String>>()
        val critic = PlanCritic { goal, _, reason -> seen.add(goal to reason); 0.5 }

        val planner = TreePlanner(listOf(a, b), critic)
        runBlocking { planner.replan(current, "s1", "step s1 failed") }

        seen.size shouldBe 2
        seen.all { it == "ship the release" to "step s1 failed" } shouldBe true
    }
})
