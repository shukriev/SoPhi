package dev.sophi.core.tools

interface Tool {
    val name: String
    val description: String
    val parametersJson: String
    suspend fun execute(argumentsJson: String): String
}
