package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession

class TuiEngine(
    private val loop: AgentLoop,
    private val slashHandler: SlashHandler,
    private val config: AgentConfig,
    private val terminal: Terminal = Terminal()
) {
    suspend fun run(session: AgentSession, lines: Sequence<String>) {
        var current = session
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.lowercase() in setOf("exit", "quit")) break
            if (trimmed.startsWith("/")) {
                current = slashHandler.handle(trimmed, current)
                continue
            }
            terminal.print(TextColors.green("Sophi: "))
            current = loop.streamTurn(current, trimmed, config) { token: String ->
                terminal.print(token)
            }
            terminal.println()
        }
    }
}
