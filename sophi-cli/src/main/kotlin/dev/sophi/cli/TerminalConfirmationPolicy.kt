package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.core.tools.ConfirmationPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TerminalConfirmationPolicy(
    private val terminal: Terminal,
    private val readAnswer: () -> String? = { readlnOrNull() }
) : ConfirmationPolicy {
    override suspend fun confirm(toolName: String, argumentsJson: String): Boolean = withContext(Dispatchers.IO) {
        terminal.println(TextColors.yellow("Sophi wants to run '$toolName' with arguments: $argumentsJson"))
        terminal.print(TextColors.yellow("Allow? [y/N] "))
        readAnswer()?.trim()?.equals("y", ignoreCase = true) == true
    }
}
