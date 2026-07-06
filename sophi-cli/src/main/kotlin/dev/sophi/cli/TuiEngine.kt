package dev.sophi.cli

import dev.sophi.core.session.AgentSession

class TuiEngine(
    private val turnController: TurnController,
    private val slashHandler: SlashHandler,
    private val input: InputSource
) {
    private companion object {
        val EXIT_COMMANDS = setOf("exit", "quit")
    }

    suspend fun run(session: AgentSession) {
        var current = session
        while (true) {
            val line = input.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.lowercase() in EXIT_COMMANDS) break
            current = if (trimmed.startsWith("/")) {
                slashHandler.handle(trimmed, current)
            } else {
                turnController.runTurn(current, trimmed)
            }
        }
    }
}
