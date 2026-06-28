package dev.sophi.core.agent

data class AgentConfig(
    val model: String,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7,
    val systemPrompt: String? = null,
    val maxToolRounds: Int = 10,
    val maxBranchLength: Int = 50
)
