package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.plan.DecompositionTrigger
import dev.sophi.core.agent.plan.PlanProgressEvent
import dev.sophi.core.agent.plan.PlanStep
import dev.sophi.core.agent.plan.StepStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class PlanProgressRendererTest : FunSpec({
    fun renderer(output: MutableList<String>): PlanProgressRenderer {
        val liveRegion = LiveRegion(StringBuilder()) { 80 }
        return PlanProgressRenderer(liveRegion) { output.add(it) }
    }

    test("StepStarted echoes the step id and instruction") {
        val output = mutableListOf<String>()
        val step = PlanStep(id = "s1", instruction = "ship the release")

        runBlocking { renderer(output).onProgress(PlanProgressEvent.StepStarted("plan_1", step)) }

        output shouldBe listOf(TextColors.cyan("▶ [s1] ship the release"))
    }

    test("StepFinished echoes the step's status and confidence") {
        val output = mutableListOf<String>()
        val step = PlanStep(id = "s1", instruction = "ship it", status = StepStatus.Done, confidence = 0.9)

        runBlocking { renderer(output).onProgress(PlanProgressEvent.StepFinished("plan_1", step)) }

        output shouldBe listOf(TextColors.gray("  [s1] Done (0.9)"))
    }

    test("reasoning tokens buffered during a step are echoed ahead of the StepFinished status line") {
        val output = mutableListOf<String>()
        val step = PlanStep(id = "s1", instruction = "ship it", status = StepStatus.Done)
        val r = renderer(output)

        runBlocking {
            r.onTurnEvent(TurnEvent.ReasoningToken("thinking about it"))
            r.onProgress(PlanProgressEvent.StepFinished("plan_1", step))
        }

        output shouldBe listOf(
            ResponseRenderer.renderReasoning("thinking about it"),
            TextColors.gray("  [s1] Done")
        )
    }

    test("a new StepStarted resets the reasoning buffer so it isn't echoed again for the next step") {
        val output = mutableListOf<String>()
        val r = renderer(output)

        runBlocking {
            r.onTurnEvent(TurnEvent.ReasoningToken("first step thoughts"))
            r.onProgress(PlanProgressEvent.StepFinished("plan_1", PlanStep(id = "s1", instruction = "a", status = StepStatus.Done)))
            output.clear()
            r.onProgress(PlanProgressEvent.StepStarted("plan_1", PlanStep(id = "s2", instruction = "b")))
            r.onProgress(PlanProgressEvent.StepFinished("plan_1", PlanStep(id = "s2", instruction = "b", status = StepStatus.Done)))
        }

        output shouldBe listOf(
            TextColors.cyan("▶ [s2] b"),
            TextColors.gray("  [s2] Done")
        )
    }

    test("a finished tool call is echoed via ResponseRenderer, matching normal-turn rendering") {
        val output = mutableListOf<String>()
        val r = renderer(output)

        runBlocking {
            r.onTurnEvent(TurnEvent.ToolCallStarted("some_tool", "{}"))
            r.onTurnEvent(TurnEvent.ToolCallFinished("some_tool", "42"))
        }

        output shouldBe listOf(ResponseRenderer.renderToolCall("some_tool", "{}", "42"))
    }

    test("a replan is echoed with the anchor step id and reason") {
        val output = mutableListOf<String>()

        runBlocking {
            renderer(output).onProgress(PlanProgressEvent.Replanned("plan_1", "s1", "step s1 failed"))
        }

        output shouldBe listOf(TextColors.yellow("↻ replanning [s1]: step s1 failed"))
    }

    test("a decomposition is echoed with the step id and the child plan id") {
        val output = mutableListOf<String>()

        runBlocking {
            renderer(output).onProgress(
                PlanProgressEvent.Decomposed("s1", "plan_2", DecompositionTrigger.Declared)
            )
        }

        output shouldBe listOf(TextColors.magenta("⤷ [s1] expanded into sub-plan plan_2"))
    }

    test("renderNow() writes a spinner reflecting accumulated tokens to the live region") {
        val sink = StringBuilder()
        val r = PlanProgressRenderer(LiveRegion(sink) { 80 }) { }

        runBlocking {
            r.onTurnEvent(TurnEvent.Token("a"))
            r.onTurnEvent(TurnEvent.Token("b"))
        }
        r.renderNow()

        sink.toString() shouldContain "2 tokens"
    }
})
