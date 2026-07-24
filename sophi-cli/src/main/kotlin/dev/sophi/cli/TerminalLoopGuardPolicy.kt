package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.core.agent.LoopGuardPolicy

class TerminalLoopGuardPolicy(
    private val terminal: Terminal,
    private val input: InputSource
) : LoopGuardPolicy {
    override suspend fun askToContinue(reason: String): Boolean {
        terminal.println(TextColors.yellow("Sophi seems stuck: $reason"))
        terminal.print(TextColors.yellow("Continue? [y/N] "))
        return input.awaitYesNo()
    }
}
