package dev.sophi.memory.tools

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.memory.jane.JanesPalace

class ListActionablePatternsTool(private val palace: JanesPalace) : Tool {
    override val name = "list_actionable_patterns"
    override val description = "List the user's remembered actionable patterns — recurring personal " +
        "tendencies tied to a future-triggerable situation (e.g. \"user always forgets X before Y\"). " +
        "Use this alongside calendar tools to check whether an upcoming event matches a known pattern."
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String {
        val patterns = palace.actionablePatterns()
        if (patterns.isEmpty()) return "No actionable patterns remembered."
        return patterns.joinToString("\n") { "- [${it.id}] ${it.text}" }
    }
}
