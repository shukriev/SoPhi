package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.AgentSession

class ChatEngine(
    private val loop: AgentLoop,
    private val config: AgentConfig
) {
    suspend fun run(
        session: AgentSession,
        lines: Sequence<String>,
        output: (String) -> Unit
    ) {
        var current = session
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.lowercase() in setOf("exit", "quit")) break
            current = loop.turn(current, trimmed, config)
            current.branch().lastOrNull()?.content?.let { output(it) }
        }
    }
}
