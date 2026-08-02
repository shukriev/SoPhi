package dev.sophi.cli

import dev.sophi.cli.streaming.StreamingTurnPresenter
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
        val presenter = StreamingTurnPresenter(autoExitTokenView)

        fun render() = liveRegion.update(presenter.renderFrame())
        render()

        val extraContext = runCatching { contextProvider(session, userInput) }.getOrNull()
        val turnConfig = if (extraContext.isNullOrBlank()) config
            else config.copy(systemPrompt = listOfNotNull(config.systemPrompt, extraContext).joinToString("\n\n"))

        // Sole writer to liveRegion while a phase is active: ticks on a fixed cadence instead of
        // once per token, so a fast token stream doesn't repaint the terminal hundreds of times.
        val animationJob = launch {
            while (isActive) {
                delay(100)
                if (!presenter.confirmationPending) render()
            }
        }

        val turnDeferred = async {
            try {
                loop.streamTurn(session, userInput, turnConfig) { event ->
                    onEvent(event)
                    when (val rendered = presenter.feed(event)) {
                        is StreamingTurnPresenter.Rendered.Cleared -> liveRegion.clear()
                        is StreamingTurnPresenter.Rendered.Redraw -> render()
                        is StreamingTurnPresenter.Rendered.ToolLine -> {
                            liveRegion.clear()
                            output(rendered.text)
                            render()
                        }
                        null -> Unit
                    }
                } to null
            } catch (e: Exception) {
                session to e
            }
        }
        val controlKeysDeferred = async {
            input.awaitControlKeys(tokenViewKey) {
                presenter.toggleTokenView()
                render()
            }
        }

        fun outputReasoningIfAny() {
            presenter.reasoningText()?.let { output(ResponseRenderer.renderReasoning(it)) }
        }

        select<AgentSession> {
            turnDeferred.onAwait { (result, error) ->
                animationJob.cancel()
                controlKeysDeferred.cancel()
                liveRegion.clear()
                outputReasoningIfAny()
                if (error != null) {
                    output(ResponseRenderer.renderText(presenter.finalText()) + " [error: ${error.message}]")
                    onTurnSettled(userInput, presenter.finalText(), error)
                    session
                } else {
                    output(ResponseRenderer.renderText(presenter.finalText()))
                    onTurnSettled(userInput, presenter.finalText(), null)
                    result
                }
            }
            controlKeysDeferred.onAwait {
                animationJob.cancel()
                turnDeferred.cancel()
                liveRegion.clear()
                outputReasoningIfAny()
                output(ResponseRenderer.renderText(presenter.finalText()) + " [interrupted]")
                onTurnSettled(userInput, presenter.finalText(), null)
                session
            }
        }
    }
}
