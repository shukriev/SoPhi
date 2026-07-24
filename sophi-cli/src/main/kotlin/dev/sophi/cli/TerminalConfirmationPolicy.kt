package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.core.tools.ConfirmationPolicy

class TerminalConfirmationPolicy(
    private val terminal: Terminal,
    private val input: InputSource
) : ConfirmationPolicy {
    override suspend fun confirm(toolName: String, argumentsJson: String): Boolean {
        terminal.println(TextColors.yellow("Sophi wants to run '$toolName' with arguments: $argumentsJson"))
        terminal.print(TextColors.yellow("Allow? [y/N] "))
        return input.awaitYesNo()
    }
}
