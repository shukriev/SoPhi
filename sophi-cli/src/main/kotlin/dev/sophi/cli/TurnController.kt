package dev.sophi.cli

import dev.sophi.cli.streaming.StreamingTurnPresenter
import dev.sophi.sdk.SophiRuntime
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionIdContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class TurnController(
    // Per-turn plugin context injection and the BEFORE/AFTER_TURN hook dispatch both live inside
    // SophiRuntime.streamTurn now, including on the interrupt path — this class only renders.
    private val runtime: SophiRuntime,
    private val input: InputSource,
    private val liveRegion: LiveRegion,
    private val onEvent: suspend (TurnEvent) -> Unit = {},
    // Keyboard shortcut that toggles the live region between the spinner+stats view and the
    // raw token stream. Default view is the spinner; press again to switch back.
    private val tokenViewKey: Char = 'T',
    // When a tool call finishes and generation resumes, reset token view back to the spinner
    // rather than carrying the toggle across the tool-call boundary.
    private val autoExitTokenView: Boolean = true,
    private val output: (String) -> Unit
) {
    suspend fun runTurn(session: AgentSession, userInput: String): AgentSession =
        coroutineScope {
            val presenter = StreamingTurnPresenter(autoExitTokenView)

            fun render() = liveRegion.update(presenter.renderFrame())
            render()

            // Sole writer to liveRegion while a phase is active: ticks on a fixed cadence instead of
            // once per token, so a fast token stream doesn't repaint the terminal hundreds of times.
            val animationJob = launch {
                while (isActive) {
                    delay(100)
                    if (!presenter.confirmationPending) render()
                }
            }

            // SessionIdContext scopes only this async: it's the branch that calls streamTurn, which
            // is what tools/confirmation policy read the context from — controlKeysDeferred and
            // animationJob never touch it.
            val turnDeferred = async(SessionIdContext(session.id)) {
                try {
                    runtime.streamTurn(session, userInput) { event ->
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
                        session
                    } else {
                        output(ResponseRenderer.renderText(presenter.finalText()))
                        result
                    }
                }
                controlKeysDeferred.onAwait {
                    animationJob.cancel()
                    turnDeferred.cancel()
                    liveRegion.clear()
                    outputReasoningIfAny()
                    // No hook dispatch here: cancelling turnDeferred makes SophiRuntime.streamTurn
                    // settle the turn itself, under NonCancellable, with the partial reply.
                    output(ResponseRenderer.renderText(presenter.finalText()) + " [interrupted]")
                    session
                }
            }
        }
}
