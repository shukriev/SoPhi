package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val DELEGATE_TOOL_NAME = "delegate_to_subagent"

@Serializable
private data class SubagentArgs(
    @SerialName("subagent_type") val subagentType: String,
    val prompt: String
)

class SubagentTool(
    private val definitions: List<AgentDefinition>,
    private val provider: LLMProvider,
    private val fullRegistry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val parentSessionId: String,
    private val parentConfig: AgentConfig,
    private val depth: Int = 0,
    private val maxDelegationDepth: Int = 3
) : Tool {

    override val name = DELEGATE_TOOL_NAME
    override val description: String = if (definitions.isEmpty()) {
        "Delegate a task to a subagent. No agent types are currently configured."
    } else {
        "Delegate a task to a subagent. Available agent types:\n" +
            definitions.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }
    override val parametersJson = """
        {"type":"object","properties":{"subagent_type":{"type":"string","description":"Which agent type to delegate to"},"description":{"type":"string","description":"Short 3-5 word description of the task"},"prompt":{"type":"string","description":"The task for the subagent to perform"}},"required":["subagent_type","prompt"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString<SubagentArgs>(argumentsJson)
        val definition = definitions.find { it.name == args.subagentType }
            ?: return "Error: unknown subagent type '${args.subagentType}'. Available: ${definitions.joinToString(", ") { it.name }}"

        if (depth >= maxDelegationDepth) {
            return "Error: max delegation depth ($maxDelegationDepth) exceeded"
        }

        val scopedRegistry = fullRegistry.subset(definition.allowedTools)
        if (definition.allowedTools.contains(DELEGATE_TOOL_NAME)) {
            scopedRegistry.register(
                SubagentTool(
                    definitions = definitions,
                    provider = provider,
                    fullRegistry = fullRegistry,
                    sessionManager = sessionManager,
                    parentSessionId = parentSessionId,
                    parentConfig = parentConfig,
                    depth = depth + 1,
                    maxDelegationDepth = maxDelegationDepth
                )
            )
        }

        val nestedLoop = AgentLoop(provider, scopedRegistry, sessionManager)
        val subSession = sessionManager.create(
            title = "subagent:${definition.name}",
            parentSessionId = parentSessionId
        )
        val config = AgentConfig(
            model = definition.model ?: parentConfig.model,
            systemPrompt = definition.systemPrompt
        )

        return try {
            val result = nestedLoop.turn(subSession, args.prompt, config)
            result.tip?.content ?: "Error: subagent produced no output"
        } finally {
            sessionManager.save(subSession)
        }
    }
}
