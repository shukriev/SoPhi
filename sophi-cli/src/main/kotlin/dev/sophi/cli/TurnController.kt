package dev.sophi.cli

import dev.sophi.cli.streaming.AnimationTimer
import dev.sophi.cli.streaming.StreamingIndicator
import dev.sophi.cli.streaming.StreamingPhase
import dev.sophi.cli.streaming.TokenStreamFormatter
import dev.sophi.cli.streaming.TokenViewToggleState
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.AgentSession
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.time.Instant

class TurnController(
    private val loop: AgentLoop,
    private val config: AgentConfig,
    private val input: InputSource,
    private val liveRegion: LiveRegion,
    private val onEvent: suspend (TurnEvent) -> Unit = {},
    // Per-turn context injection (memory etc.): non-null result is appended to the system
    // prompt for THIS turn only. Failures are swallowed — context must never break a turn.
    private val contextProvider: suspend (AgentSession, String) -> String? = { _, _ -> null },
    // Fired once per turn after it settles, now carrying both sides of the exchange so
    // AFTER_TURN hooks (learning outcome, memory encoding) can see the full turn.
    private val onTurnSettled: suspend (userInput: String, assistantReply: String, error: Throwable?) -> Unit =
        { _, _, _ -> },
    // Keyboard shortcut that toggles the live region between the spinner+stats view and the
    // raw token stream. Default view is the spinner; press again to switch back.
    private val tokenViewKey: Char = 'T',
    // When a tool call finishes and generation resumes, reset token view back to the spinner
    // rather than carrying the toggle across the tool-call boundary.
    private val autoExitTokenView: Boolean = true,
    private val output: (String) -> Unit
) {
    suspend fun runTurn(session: AgentSession, userInput: String): AgentSession = coroutineScope {
        val buffer = StringBuilder()
        val reasoningBuffer = StringBuilder()
        val pendingArgs = mutableMapOf<String, String>()
        var tokenCount = 0
        var reasoningTokenCount = 0
        var currentPhase: StreamingPhase? = StreamingPhase.Generating()
        var tokenViewState = TokenViewToggleState()
        val animationTimer = AnimationTimer()
        // Set while AgentLoop is blocked in confirmationPolicy.confirm() — a blocking, raw-mode
        // y/N prompt is on screen at that point (TerminalConfirmationPolicy), and the animation
        // job redrawing the spinner over it would garble the prompt and could clip the terminal's
        // own tracking of how many lines to erase on its next redraw.
        var confirmationPending = false

        fun render() {
            val phase = currentPhase ?: return
            val display = if (tokenViewState.isViewingTokens && phase is StreamingPhase.Generating) {
                TokenStreamFormatter.renderTokenStream(phase, reasoningBuffer.toString(), buffer.toString())
            } else {
                StreamingIndicator.renderSpinner(phase, animationTimer.nextFrame())
            }
            liveRegion.update(display)
        }
        render()

        val extraContext = runCatching { contextProvider(session, userInput) }.getOrNull()
        val turnConfig = if (extraContext.isNullOrBlank()) config
            else config.copy(systemPrompt = listOfNotNull(config.systemPrompt, extraContext).joinToString("\n\n"))

        // Sole writer to liveRegion while a phase is active: ticks on a fixed cadence instead of
        // once per token, so a fast token stream doesn't repaint the terminal hundreds of times.
        val animationJob = launch {
            while (isActive) {
                delay(100)
                if (!confirmationPending) render()
            }
        }

        val turnDeferred = async {
            try {
                loop.streamTurn(session, userInput, turnConfig) { event ->
                    onEvent(event)
                    when (event) {
                        is TurnEvent.Token -> {
                            buffer.append(event.text)
                            tokenCount++
                            val startTime = (currentPhase as? StreamingPhase.Generating)?.startTime ?: Instant.now()
                            currentPhase = StreamingPhase.Generating(
                                tokenCount = tokenCount, reasoningTokenCount = reasoningTokenCount, startTime = startTime
                            )
                        }
                        is TurnEvent.ReasoningToken -> {
                            reasoningBuffer.append(event.text)
                            reasoningTokenCount++
                            val startTime = (currentPhase as? StreamingPhase.Generating)?.startTime ?: Instant.now()
                            currentPhase = StreamingPhase.Generating(
                                tokenCount = tokenCount, reasoningTokenCount = reasoningTokenCount, startTime = startTime
                            )
                        }
                        is TurnEvent.ConfirmationStarted -> {
                            confirmationPending = true
                            liveRegion.clear()
                        }
                        is TurnEvent.ConfirmationFinished -> {
                            confirmationPending = false
                            render()
                        }
                        is TurnEvent.ToolCallStarted -> {
                            pendingArgs[event.name] = event.argsJson
                            currentPhase = StreamingPhase.ExecutingTool(event.name)
                        }
                        is TurnEvent.ToolCallFinished -> {
                            liveRegion.clear()
                            val args = pendingArgs.remove(event.name) ?: ""
                            output(ResponseRenderer.renderToolCall(event.name, args, event.result))
                            if (autoExitTokenView) tokenViewState = TokenViewToggleState()
                            currentPhase = StreamingPhase.Generating(tokenCount = tokenCount, reasoningTokenCount = reasoningTokenCount)
                            render()
                        }
                    }
                } to null
            } catch (e: Exception) {
                session to e
            }
        }
        val controlKeysDeferred = async {
            input.awaitControlKeys(tokenViewKey) {
                tokenViewState = tokenViewState.toggle()
                render()
            }
        }

        fun outputReasoningIfAny() {
            if (reasoningBuffer.isNotEmpty()) output(ResponseRenderer.renderReasoning(reasoningBuffer.toString()))
        }

        select<AgentSession> {
            turnDeferred.onAwait { (result, error) ->
                animationJob.cancel()
                controlKeysDeferred.cancel()
                liveRegion.clear()
                outputReasoningIfAny()
                if (error != null) {
                    output(ResponseRenderer.renderText(buffer.toString()) + " [error: ${error.message}]")
                    onTurnSettled(userInput, buffer.toString(), error)
                    session
                } else {
                    output(ResponseRenderer.renderText(buffer.toString()))
                    onTurnSettled(userInput, buffer.toString(), null)
                    result
                }
            }
            controlKeysDeferred.onAwait {
                animationJob.cancel()
                turnDeferred.cancel()
                liveRegion.clear()
                outputReasoningIfAny()
                output(ResponseRenderer.renderText(buffer.toString()) + " [interrupted]")
                onTurnSettled(userInput, buffer.toString(), null)
                session
            }
        }
    }
}
