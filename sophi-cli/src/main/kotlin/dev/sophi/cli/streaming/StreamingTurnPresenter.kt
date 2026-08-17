package dev.sophi.cli.streaming

import dev.sophi.cli.ResponseRenderer
import dev.sophi.core.agent.TurnEvent
import java.time.Instant

/**
 * Extracted from TurnController.runTurn's inline render state so GoalController can drive the
 * identical spinner/token/tool-call rendering per plan step. Behavior-preserving: every branch
 * here matches what TurnController's own `render()`/`when(event)` block did inline before this
 * extraction.
 */
class StreamingTurnPresenter(private val autoExitTokenView: Boolean = true) {
    private val buffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val pendingArgs = mutableMapOf<String, String>()
    private var tokenCount = 0
    private var reasoningTokenCount = 0
    private var currentPhase: StreamingPhase? = StreamingPhase.Generating()
    private var tokenViewState = TokenViewToggleState()
    private val animationTimer = AnimationTimer()
    var confirmationPending: Boolean = false; private set

    sealed class Rendered {
        object Cleared : Rendered()
        object Redraw : Rendered()
        data class ToolLine(val text: String) : Rendered()
    }

    fun feed(event: TurnEvent): Rendered? = when (event) {
        is TurnEvent.Token -> {
            buffer.append(event.text); tokenCount++; bumpGenerating(); null
        }
        is TurnEvent.ReasoningToken -> {
            reasoningBuffer.append(event.text); reasoningTokenCount++; bumpGenerating(); null
        }
        is TurnEvent.ConfirmationStarted -> {
            confirmationPending = true
            Rendered.Cleared
        }
        is TurnEvent.ConfirmationFinished -> {
            confirmationPending = false
            Rendered.Redraw
        }
        is TurnEvent.ToolCallStarted -> {
            pendingArgs[event.name] = event.argsJson
            currentPhase = StreamingPhase.ExecutingTool(event.name)
            Rendered.Cleared
        }
        is TurnEvent.ToolCallFinished -> {
            val args = pendingArgs.remove(event.name) ?: ""
            if (autoExitTokenView) tokenViewState = TokenViewToggleState()
            currentPhase = StreamingPhase.Generating(tokenCount = tokenCount, reasoningTokenCount = reasoningTokenCount)
            Rendered.ToolLine(ResponseRenderer.renderToolCall(event.name, args, event.result))
        }
    }

    private fun bumpGenerating() {
        val startTime = (currentPhase as? StreamingPhase.Generating)?.startTime ?: Instant.now()
        currentPhase = StreamingPhase.Generating(
            tokenCount = tokenCount, reasoningTokenCount = reasoningTokenCount, startTime = startTime
        )
    }

    fun toggleTokenView() {
        tokenViewState = tokenViewState.toggle()
    }

    fun renderFrame(): String {
        val phase = currentPhase ?: return ""
        return if (tokenViewState.isViewingTokens && phase is StreamingPhase.Generating) {
            TokenStreamFormatter.renderTokenStream(phase, reasoningBuffer.toString(), buffer.toString())
        } else {
            StreamingIndicator.renderSpinner(phase, animationTimer.nextFrame())
        }
    }

    fun finalText(): String = buffer.toString()

    fun reasoningText(): String? = reasoningBuffer.toString().takeIf { it.isNotEmpty() }
}
