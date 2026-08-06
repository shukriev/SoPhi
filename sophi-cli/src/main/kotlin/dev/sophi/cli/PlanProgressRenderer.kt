package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import dev.sophi.cli.streaming.AnimationTimer
import dev.sophi.cli.streaming.StreamingIndicator
import dev.sophi.cli.streaming.StreamingPhase
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.plan.PlanProgressEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant

/**
 * Console feedback for /plan: a whole run chains many agentLoop.turn calls (planning, per-step
 * tool rounds, critique, replanning, decomposition) with no single "turn" to hang a spinner off,
 * so this mirrors TurnController's rendering but reset at each step boundary instead of once per
 * turn, plus echoes PlanRunner's step/replan/decomposition boundaries so a long run isn't silent
 * between steps.
 */
class PlanProgressRenderer(
    private val liveRegion: LiveRegion,
    private val output: (String) -> Unit
) {
    private val reasoningBuffer = StringBuilder()
    private var tokenCount = 0
    private var reasoningTokenCount = 0
    private var currentPhase: StreamingPhase = StreamingPhase.Generating()
    private var confirmationPending = false
    private val pendingArgs = mutableMapOf<String, String>()
    private val animationTimer = AnimationTimer()

    suspend fun animate() {
        while (currentCoroutineContext().isActive) {
            delay(100)
            if (!confirmationPending) renderNow()
        }
    }

    fun renderNow() {
        liveRegion.update(StreamingIndicator.renderSpinner(currentPhase, animationTimer.nextFrame()))
    }

    suspend fun onTurnEvent(event: TurnEvent) {
        when (event) {
            is TurnEvent.Token -> {
                tokenCount++
                bumpGeneratingPhase()
            }
            is TurnEvent.ReasoningToken -> {
                reasoningBuffer.append(event.text)
                reasoningTokenCount++
                bumpGeneratingPhase()
            }
            is TurnEvent.ConfirmationStarted -> {
                confirmationPending = true
                liveRegion.clear()
            }
            is TurnEvent.ConfirmationFinished -> {
                confirmationPending = false
                renderNow()
            }
            is TurnEvent.ToolCallStarted -> {
                pendingArgs[event.name] = event.argsJson
                currentPhase = StreamingPhase.ExecutingTool(event.name)
            }
            is TurnEvent.ToolCallFinished -> {
                liveRegion.clear()
                val args = pendingArgs.remove(event.name) ?: ""
                output(ResponseRenderer.renderToolCall(event.name, args, event.result))
                currentPhase = StreamingPhase.Generating(tokenCount, reasoningTokenCount)
            }
        }
    }

    suspend fun onProgress(event: PlanProgressEvent) {
        liveRegion.clear()
        when (event) {
            is PlanProgressEvent.StepStarted -> {
                reasoningBuffer.clear()
                tokenCount = 0
                reasoningTokenCount = 0
                currentPhase = StreamingPhase.Generating()
                output(TextColors.cyan("▶ [${event.step.id}] ${event.step.instruction}"))
            }
            is PlanProgressEvent.StepFinished -> {
                if (reasoningBuffer.isNotEmpty()) output(ResponseRenderer.renderReasoning(reasoningBuffer.toString()))
                val confidence = event.step.confidence?.let { " ($it)" } ?: ""
                output(TextColors.gray("  [${event.step.id}] ${event.step.status}$confidence"))
            }
            is PlanProgressEvent.Replanned ->
                output(TextColors.yellow("↻ replanning [${event.stepId}]: ${event.reason}"))
            is PlanProgressEvent.Decomposed ->
                output(TextColors.magenta("⤷ [${event.stepId}] expanded into sub-plan ${event.childPlanId}"))
        }
    }

    private fun bumpGeneratingPhase() {
        val startTime = (currentPhase as? StreamingPhase.Generating)?.startTime ?: Instant.now()
        currentPhase = StreamingPhase.Generating(tokenCount, reasoningTokenCount, startTime)
    }
}
