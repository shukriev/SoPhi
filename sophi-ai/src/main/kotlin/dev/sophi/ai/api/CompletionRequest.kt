package dev.sophi.ai.api

data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String
)

data class CompletionRequest(
    val messages: List<Message>,
    val model: String,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7,
    val systemPrompt: String? = null,
    val tools: List<ToolDefinition> = emptyList()
)
