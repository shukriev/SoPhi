package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.core.audit.noteDecision
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest

class TerminalConfirmationPolicy(
    private val terminal: Terminal,
    private val input: InputSource
) : ConfirmationPolicy {
    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> {
        if (requests.isEmpty()) return emptyMap()
        if (requests.size == 1) {
            val r = requests.single()
            terminal.println(TextColors.yellow("Sophi wants to run '${r.toolName}' (${r.riskLevel}): ${r.preview ?: r.argumentsJson}"))
            terminal.print(TextColors.yellow("Allow? [y/N] "))
            val allow = input.awaitYesNo()
            noteDecision(r.callId, "user")
            return mapOf(r.callId to allow)
        }
        terminal.println(TextColors.yellow("Sophi wants to run ${requests.size} actions this round:"))
        requests.forEach { r ->
            terminal.println(TextColors.yellow("  - ${r.toolName} (${r.riskLevel}): ${r.preview ?: r.argumentsJson}"))
        }
        terminal.print(TextColors.yellow("Allow all? [y/N] "))
        val allowAll = input.awaitYesNo()
        requests.forEach { noteDecision(it.callId, "user") }
        return requests.associate { it.callId to allowAll }
    }
}
