package dev.sophi.core.tools

enum class RiskLevel { SAFE, DESTRUCTIVE }

interface Tool {
    val name: String
    val description: String
    val parametersJson: String
    val riskLevel: RiskLevel get() = RiskLevel.SAFE
    suspend fun execute(argumentsJson: String): String
}
