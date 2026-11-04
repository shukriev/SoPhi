package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.undo.RoundChanges
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

private const val DELEGATE_TOOL_NAME = "delegate_to_subagent"
private const val WORKTREE_PARAM = ""","worktree":{"type":"boolean","description":"Run the subagent in its own git worktree off HEAD, so its file changes land on a branch you can review and merge instead of in the working tree"}"""

@Serializable
private data class SubagentArgs(
    @SerialName("subagent_type") val subagentType: String,
    val prompt: String,
    @SerialName("expected_tools") val expectedTools: List<String>? = null,
    val worktree: Boolean = false,
)

class SubagentTool(
    private val definitions: List<AgentDefinition>,
    private val provider: LLMProvider,
    private val fullRegistry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val parentConfig: AgentConfig,
    /**
     * Total context window of the model the nested loop will use, in tokens. Threaded unchanged
     * to every deeper level: a subagent runs on the same provider/model family as its parent
     * unless its definition overrides `model`, and there is deliberately no per-model registry.
     */
    private val contextWindowTokens: Int,
    private val depth: Int = 0,
    private val maxDelegationDepth: Int = 3,
    private val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL,
    /** The parent's window now, when a host can switch model mid-session; null = [contextWindowTokens]. */
    private val currentWindow: (() -> Int)? = null,
    /** Non-null: the model may ask for a subagent in its own git worktree (`worktree: true`). */
    private val worktrees: SubagentWorktrees? = null,
) : Tool {

    override val name = DELEGATE_TOOL_NAME
    override val description: String = if (definitions.isEmpty()) {
        "Delegate a task to a subagent. No agent types are currently configured."
    } else {
        "Delegate a task to a subagent. Available agent types:\n" +
            definitions.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }
    override val parametersJson = """
        {"type":"object","properties":{"subagent_type":{"type":"string","description":"Which agent type to delegate to"},"description":{"type":"string","description":"Short 3-5 word description of the task"},"prompt":{"type":"string","description":"The task for the subagent to perform"},"expected_tools":{"type":"array","items":{"type":"string"},"description":"Tool names you expect this subagent to need, if known — declaring them upfront lets it use them without asking again for each call"}${if (worktrees == null) "" else WORKTREE_PARAM}},"required":["subagent_type","prompt"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override fun riskLevel(argumentsJson: String): RiskLevel {
        val args = runCatching { json.decodeFromString<SubagentArgs>(argumentsJson) }.getOrNull()
            ?: return RiskLevel.SAFE
        return fullRegistry.worstRiskAmong(args.expectedTools)
    }

    override suspend fun execute(argumentsJson: String): String {
        val parentSessionId = coroutineContext[SessionIdContext]?.sessionId
            ?: error("no SessionIdContext — the host must wrap turns in it")
        val args = json.decodeFromString<SubagentArgs>(argumentsJson)
        val definition = definitions.find { it.name == args.subagentType }
            ?: return "Error: unknown subagent type '${args.subagentType}'. Available: ${definitions.joinToString(", ") { it.name }}"

        if (depth >= maxDelegationDepth) {
            return "Error: max delegation depth ($maxDelegationDepth) exceeded"
        }

        val worktree = if (args.worktree && worktrees != null) {
            try {
                worktrees.open(definition.name)
            } catch (e: IllegalStateException) {
                return "Error: worktree: ${e.message}. Delegate again without worktree to work in the shared tree."
            }
        } else null
        // In a worktree, the file/shell tools are rooted there instead, for this subagent and any it delegates to.
        val registry = if (worktree == null) fullRegistry else try {
            fullRegistry.subset(fullRegistry.names()).also { r ->
                worktrees!!.toolsAt(worktree.root).filter { r.getOrNull(it.name) != null }.forEach { r.register(it) }
            }
        } catch (e: Exception) {
            worktree.finish("")
            return "Error: worktree: ${e.message}. Delegate again without worktree to work in the shared tree."
        }
        val scopedRegistry = registry.subset(definition.allowedTools)
        if (definition.allowedTools.contains(DELEGATE_TOOL_NAME)) {
            scopedRegistry.register(
                SubagentTool(
                    definitions = definitions,
                    provider = provider,
                    fullRegistry = registry,
                    sessionManager = sessionManager,
                    parentConfig = parentConfig,
                    contextWindowTokens = contextWindowTokens,
                    depth = depth + 1,
                    maxDelegationDepth = maxDelegationDepth,
                    confirmationPolicy = confirmationPolicy,
                    currentWindow = currentWindow,
                    // a subagent in a worktree shares it with its own subagents
                    worktrees = worktrees.takeIf { worktree == null },
                )
            )
        }

        val nestedLoop = AgentLoop(
            provider, scopedRegistry, sessionManager,
            confirmationPolicy = confirmationPolicy,
            grants = scopedRegistry.safeGrantsFrom(args.expectedTools),
            contextWindowTokens = currentWindow?.invoke() ?: contextWindowTokens
        )
        val subSession = sessionManager.create(
            title = "subagent:${definition.name}",
            parentSessionId = parentSessionId
        )
        val config = AgentConfig(
            model = definition.model ?: parentConfig.model,
            systemPrompt = definition.systemPrompt
        )

        if (worktree == null) return try {
            val result = nestedLoop.turn(subSession, args.prompt, config)
            result.tip?.content ?: "Error: subagent produced no output"
        } finally {
            sessionManager.save(subSession)
        }
        var answer: String? = null
        try {
            // Its own RoundChanges, no parent: /undo restores the main tree, and the worktree's changes live on its branch.
            answer = withContext(RoundChanges()) { nestedLoop.turn(subSession, args.prompt, config) }.tip?.content
                ?: "Error: subagent produced no output"
        } finally {
            sessionManager.save(subSession)
            // finish is blocking git; it runs whether the turn returned, threw or was cancelled
            val line = worktree.finish("subagent ${definition.name}: ${args.prompt}")
            if (answer != null) answer = "$answer\n\n$line"
        }
        return answer!!
    }
}
