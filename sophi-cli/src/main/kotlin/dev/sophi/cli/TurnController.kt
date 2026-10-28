package dev.sophi.cli

import dev.sophi.cli.streaming.StreamingTurnPresenter
import dev.sophi.sdk.SophiRuntime
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.agent.TurnInbox
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
    private val tokenViewKey: Char = DEFAULT_TOKEN_VIEW_KEY,
    // When a tool call finishes and generation resumes, reset token view back to the spinner
    // rather than carrying the toggle across the tool-call boundary.
    private val autoExitTokenView: Boolean = true,
    private val output: (String) -> Unit
) {
    private var draft = ""

    /** Text the user typed during the last turn that never reached the model; "" once read. */
    fun takeDraft(): String = draft.also { draft = "" }

    suspend fun runTurn(session: AgentSession, userInput: String): AgentSession {
        val inbox = TurnInbox()
        val editor = SteerEditor(inbox)
        // The draft is taken once coroutineScope returns, i.e. after a cancelled turn has settled:
        // a follow-up cancelled before its loop started is put back in the inbox only then.
        return coroutineScope {
            var presenter = StreamingTurnPresenter(autoExitTokenView)

            fun render() {
                val queue = editor.render()
                liveRegion.update(presenter.renderFrame() + if (queue.isEmpty()) "" else "\n" + queue)
            }
            render()

            fun outputReasoningIfAny() {
                presenter.reasoningText()?.let { output(ResponseRenderer.renderReasoning(it)) }
            }

            // A steer or follow-up starts a new stretch of the reply: settle what streamed so far,
            // print the user's words where they landed in the conversation, then stream afresh.
            fun startSegment(texts: List<String>) {
                liveRegion.clear()
                outputReasoningIfAny()
                presenter.finalText().takeIf { it.isNotEmpty() }?.let { output(ResponseRenderer.renderText(it)) }
                texts.forEach { output("You: $it") }
                presenter = StreamingTurnPresenter(autoExitTokenView)
                render()
            }

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
                    runtime.streamTurn(session, userInput, inbox) { event ->
                        onEvent(event)
                        when (event) {
                            is TurnEvent.SteerApplied -> startSegment(event.texts)
                            is TurnEvent.FollowUpStarted -> startSegment(listOf(event.text))
                            else -> when (val rendered = presenter.feed(event)) {
                                is StreamingTurnPresenter.Rendered.Cleared -> liveRegion.clear()
                                is StreamingTurnPresenter.Rendered.Redraw -> render()
                                is StreamingTurnPresenter.Rendered.ToolLine -> {
                                    liveRegion.clear()
                                    output(rendered.text)
                                    render()
                                }
                                null -> Unit
                            }
                        }
                    } to null
                } catch (e: Exception) {
                    session to e
                }
            }
            val controlKeysDeferred = async {
                input.awaitControlKeys(tokenViewKey, onToggle = {
                    presenter.toggleTokenView()
                    render()
                }, onKey = {
                    editor.key(it)
                    if (!presenter.confirmationPending) render()
                })
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
        }.also { draft = editor.takeDraft() }
    }
}
