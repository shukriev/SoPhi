package dev.sophi.core.agent.plan

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class LlmPlannerTest : FunSpec({
    test("plan parses an ordered step list with dependencies from the model response") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text(
            """{"steps":[{"id":"s1","instruction":"branch"},{"id":"s2","instruction":"changelog","dependsOn":["s1"]}]}""",
            TokenUsage(1, 1))
        val planner = LlmPlanner(provider, model = "test-model")

        val plan = runBlocking { planner.plan("ship the release") }
        plan.steps shouldHaveSize 2
        plan.steps[1].dependsOn shouldBe listOf("s1")
        plan.version shouldBe 1
        plan.parentPlanId shouldBe null
    }

    test("plan retries once with a stricter prompt when the first response is unparseable") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returnsMany listOf(
            LLMResponse.Text("sure, here's a plan for you:", TokenUsage(1, 1)),
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1))
        )
        val planner = LlmPlanner(provider, model = "test-model")

        val plan = runBlocking { planner.plan("goal") }
        plan.steps shouldHaveSize 1
    }

    test("plan falls back to a single trivial step when both attempts are unparseable") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("nope", TokenUsage(1, 1))
        val planner = LlmPlanner(provider, model = "test-model")

        val plan = runBlocking { planner.plan("do the thing") }
        plan.steps shouldHaveSize 1
        plan.steps.single().instruction shouldBe "do the thing"
    }

    test("plan falls back to a single trivial step when the model returns an empty step list") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("""{"steps":[]}""", TokenUsage(1, 1))
        val planner = LlmPlanner(provider, model = "test-model")

        val plan = runBlocking { planner.plan("do the thing") }
        plan.steps shouldHaveSize 1
    }

    test("plan uses contextProvider when no explicit context is passed") {
        val provider = mockk<LLMProvider>()
        val capturedPrompts = mutableListOf<String>()
        coEvery { provider.complete(any()) } coAnswers {
            capturedPrompts.add(firstArg<CompletionRequest>().messages.first().content)
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1))
        }
        val planner = LlmPlanner(provider, model = "test-model",
            contextProvider = { listOf("user prefers concise changelogs") })

        runBlocking { planner.plan("ship the release") }
        capturedPrompts.single() shouldContain "user prefers concise changelogs"
    }

    test("replan keeps Done steps unchanged and bumps the version") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text(
            """{"steps":[{"id":"s2b","instruction":"retry the notification, using #general instead"}]}""",
            TokenUsage(1, 1))
        val planner = LlmPlanner(provider, model = "test-model")
        val current = Plan(
            id = "plan_1", goalPrompt = "ship it",
            steps = listOf(
                PlanStep(id = "s1", instruction = "branch", status = StepStatus.Done, confidence = 1.0),
                PlanStep(id = "s2", instruction = "notify team", status = StepStatus.Failed)
            )
        )

        val replanned = runBlocking { planner.replan(current, "s2", "notification channel missing", emptyList()) }
        replanned.version shouldBe 2
        replanned.parentPlanId shouldBe "plan_1"
        replanned.steps shouldHaveSize 2
        replanned.steps[0] shouldBe current.steps[0]
        replanned.steps[1].instruction shouldBe "retry the notification, using #general instead"
    }

    test("replan falls back to a retry step referencing the anchor's instruction when unparseable") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("nope", TokenUsage(1, 1))
        val planner = LlmPlanner(provider, model = "test-model")
        val current = Plan(id = "plan_1", goalPrompt = "goal",
            steps = listOf(PlanStep(id = "s1", instruction = "notify team", status = StepStatus.Failed)))

        val replanned = runBlocking { planner.replan(current, "s1", "channel missing", emptyList()) }
        replanned.steps.single().id shouldBe "s1"
        replanned.steps.single().instruction shouldBe "Retry: notify team"
    }

    test("plan parses the decompose flag and defaults it to false when omitted") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text(
            """{"steps":[{"id":"s1","instruction":"process every ticket","decompose":true},""" +
                """{"id":"s2","instruction":"post a summary"}]}""",
            TokenUsage(1, 1))
        val planner = LlmPlanner(provider, model = "test-model")

        val plan = runBlocking { planner.plan("clear the backlog") }
        plan.steps[0].decompose shouldBe true
        plan.steps[1].decompose shouldBe false
    }

    test("the plan prompt documents the decompose field and discourages over-marking") {
        val provider = mockk<LLMProvider>()
        val capturedPrompts = mutableListOf<String>()
        coEvery { provider.complete(any()) } coAnswers {
            capturedPrompts.add(firstArg<CompletionRequest>().messages.first().content)
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1))
        }
        val planner = LlmPlanner(provider, model = "test-model")

        runBlocking { planner.plan("goal") }
        capturedPrompts.single() shouldContain "\"decompose\""
        capturedPrompts.single() shouldContain "Most steps are false."
    }

    test("the plan prompt tells the model to discover-then-decompose for goals that enumerate many items") {
        val provider = mockk<LLMProvider>()
        val capturedPrompts = mutableListOf<String>()
        coEvery { provider.complete(any()) } coAnswers {
            capturedPrompts.add(firstArg<CompletionRequest>().messages.first().content)
            LLMResponse.Text("""{"steps":[{"id":"s1","instruction":"do it"}]}""", TokenUsage(1, 1))
        }
        val planner = LlmPlanner(provider, model = "test-model")

        runBlocking { planner.plan("goal") }
        capturedPrompts.single() shouldContain "each X"
        capturedPrompts.single() shouldContain "isn't known yet"
        capturedPrompts.single() shouldContain "discovers"
    }

    test("the unparseable-response fallback step is never marked for decomposition") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns LLMResponse.Text("nope", TokenUsage(1, 1))
        val planner = LlmPlanner(provider, model = "test-model")

        val plan = runBlocking { planner.plan("do the thing") }
        plan.steps.single().decompose shouldBe false
    }
})
