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
    val tools: List<ToolDefinition> = emptyList(),
    /** OpenAI-compat only: "none"/"low"/"medium"/"high". Ignored by other providers. */
    val reasoningEffort: String? = null,
    /**
     * The System-1 judgment this call is ([dev.sophi.ai.system1.Judgments]); null = not a judgment,
     * always served by System 2. Providers ignore it; only [dev.sophi.ai.system1.DualSystemProvider] reads it.
     */
    val judgment: String? = null
)
