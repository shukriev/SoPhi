package dev.sophi.core.tools

enum class RiskLevel { SAFE, CAUTION, DESTRUCTIVE }

interface Tool {
    val name: String
    val description: String
    val parametersJson: String
    fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.SAFE
    suspend fun execute(argumentsJson: String): String
}
