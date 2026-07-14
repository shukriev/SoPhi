package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.AgentSession
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val output: (String) -> Unit
) {
    suspend fun runTurn(session: AgentSession, userInput: String): AgentSession = coroutineScope {
        val buffer = StringBuilder()
        val pendingArgs = mutableMapOf<String, String>()
        liveRegion.update("Sophi is thinking…")

        val extraContext = runCatching { contextProvider(session, userInput) }.getOrNull()
        val turnConfig = if (extraContext.isNullOrBlank()) config
            else config.copy(systemPrompt = listOfNotNull(config.systemPrompt, extraContext).joinToString("\n\n"))

        val turnDeferred = async {
            try {
                loop.streamTurn(session, userInput, turnConfig) { event ->
                    onEvent(event)
                    when (event) {
                        is TurnEvent.Token -> {
                            buffer.append(event.text)
                            liveRegion.update(buffer.toString())
                        }
                        is TurnEvent.ToolCallStarted -> {
                            pendingArgs[event.name] = event.argsJson
                            liveRegion.update("⚙ Running ${event.name}…")
                        }
                        is TurnEvent.ToolCallFinished -> {
                            liveRegion.clear()
                            val args = pendingArgs.remove(event.name) ?: ""
                            output(ResponseRenderer.renderToolCall(event.name, args, event.result))
                            liveRegion.update(buffer.toString())
                        }
                    }
                } to null
            } catch (e: Exception) {
                session to e
            }
        }
        val escDeferred = async { input.awaitEsc() }

        select<AgentSession> {
            turnDeferred.onAwait { (result, error) ->
                escDeferred.cancel()
                liveRegion.clear()
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
            escDeferred.onAwait {
                turnDeferred.cancel()
                liveRegion.clear()
                output(ResponseRenderer.renderText(buffer.toString()) + " [interrupted]")
                onTurnSettled(userInput, buffer.toString(), null)
                session
            }
        }
    }
}
