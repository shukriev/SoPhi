package dev.sophi.memory.tools

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.memory.jane.JanesPalace

class ListOpenCommitmentsTool(private val palace: JanesPalace) : Tool {
    override val name = "list_open_commitments"
    override val description = "List the user's tracked commitments that are still open — things " +
        "they said they'd do, not yet dismissed or expired. Use this to draft a follow-up for any " +
        "commitment that doesn't already have a scheduled task."
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String {
        val commitments = palace.openCommitments()
        if (commitments.isEmpty()) return "No open commitments."
        return commitments.joinToString("\n") { "- [${it.id}] ${it.text}" }
    }
}
