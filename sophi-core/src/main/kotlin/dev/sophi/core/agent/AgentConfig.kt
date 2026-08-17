package dev.sophi.core.agent

data class AgentConfig(
    val model: String,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7,
    val systemPrompt: String? = null,
    // A sanity ceiling, not the primary bound: context usage + mid-loop compaction in AgentLoop
    // is what actually bounds a turn. Hitting this stops the turn gracefully (work is saved).
    val maxToolRounds: Int = 200,
    val maxBranchLength: Int = 50
)
