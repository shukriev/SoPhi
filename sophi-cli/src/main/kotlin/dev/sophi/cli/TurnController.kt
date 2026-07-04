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
    private val output: (String) -> Unit
) {
    suspend fun runTurn(session: AgentSession, userInput: String): AgentSession = coroutineScope {
        val buffer = StringBuilder()
        val pendingArgs = mutableMapOf<String, String>()
        liveRegion.update("Sophi is thinking…")

        val turnDeferred = async {
            try {
                loop.streamTurn(session, userInput, config) { event ->
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
                    session
                } else {
                    output(ResponseRenderer.renderText(buffer.toString()))
                    result
                }
            }
            escDeferred.onAwait {
                turnDeferred.cancel()
                liveRegion.clear()
                output(ResponseRenderer.renderText(buffer.toString()) + " [interrupted]")
                session
            }
        }
    }
}
