package dev.sophi.schedule.tools

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool

class ProposeImprovementTool : Tool {
    override val name = "propose_improvement"
    override val description =
        "Submit a structured, evidence-backed proposal for a change to Sophi itself, for human " +
            "review. Use once, when you have a concrete finding — not for routine research notes."
    override val parametersJson = """
        {"type":"object","properties":{"title":{"type":"string"},"category":{"type":"string","enum":["lesson-quality","tool-reliability","prompt","process","other"]},"rationale":{"type":"string"},"suggestedAction":{"type":"string"}},"required":["title","category","rationale","suggestedAction"]}
    """.trimIndent()
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE
    override suspend fun execute(argumentsJson: String): String = "Proposal recorded for review."
}
