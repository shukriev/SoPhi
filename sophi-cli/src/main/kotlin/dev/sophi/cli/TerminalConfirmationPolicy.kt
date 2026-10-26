package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.core.audit.noteDecision
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest

class TerminalConfirmationPolicy(
    private val terminal: Terminal,
    private val input: InputSource,
    private val rules: PermissionRules? = null
) : ConfirmationPolicy {
    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> {
        if (requests.isEmpty()) return emptyMap()
        val single = requests.size == 1
        if (single) {
            val r = requests.single()
            terminal.println(TextColors.yellow("Sophi wants to run '${r.toolName}' (${r.riskLevel}): ${r.preview ?: r.argumentsJson}"))
        } else {
            terminal.println(TextColors.yellow("Sophi wants to run ${requests.size} actions this round:"))
            requests.forEach { r ->
                terminal.println(TextColors.yellow("  - ${r.toolName} (${r.riskLevel}): ${r.preview ?: r.argumentsJson}"))
            }
        }
        val offerAlways = rules != null && requests.all { it.alwaysAllow != null }
        val toWrite = if (offerAlways) requests.flatMap { it.alwaysAllow.orEmpty() } else emptyList()
        toWrite.forEach {
            terminal.println(TextColors.yellow("  a writes to .sophi/permissions.json: ${it.tool} ${it.pattern ?: "(any call)"}"))
        }
        val question = if (single) "Allow?" else "Allow all?"
        terminal.print(TextColors.yellow("$question [y/N${if (offerAlways) "/a=always" else ""}] "))
        val answer = input.awaitConfirmation(offerAlways)
        if (answer == ConfirmAnswer.ALWAYS) {
            try {
                rules!!.addProjectAllows(toWrite)
            } catch (e: Exception) {
                terminal.println(TextColors.yellow("Could not save the rule: ${e.message}"))
            }
        }
        val allow = answer != ConfirmAnswer.NO
        requests.forEach { noteDecision(it.callId, "user") }
        return requests.associate { it.callId to allow }
    }
}
