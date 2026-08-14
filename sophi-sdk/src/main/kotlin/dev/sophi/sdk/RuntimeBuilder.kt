package dev.sophi.sdk

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.probeEmbeddingProvider
import dev.sophi.ai.providers.buildOpenAiCompatEmbeddingProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.LoopGuardPolicy
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.SophiPlugin
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.config.McpConfigLoader
import dev.sophi.memory.MemoryPlugin
import dev.sophi.memory.MemoryPromptSection
import dev.sophi.memory.jane.JanesPalace
import dev.sophi.memory.jane.JanesPalaceConfig
import dev.sophi.schedule.store.TaskStore
import dev.sophi.schedule.tools.ScheduleTaskTool
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class RuntimeBuilder {
    var provider: LLMProvider? = null
    var model: String = "claude-sonnet-4-5"
    var maxTokens: Int = 4096
    var systemPrompt: String? = null
    var sessionsDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "sessions")
    var skillsDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "skills")

    private val tools: MutableList<Tool> = mutableListOf()
    private val plugins: MutableList<SophiPlugin> = mutableListOf()
    private var confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.DENY_ALL
    private var grants: Set<String> = emptySet()
    private var mcpConfigPath: Path? = null
    private var mcpClientManager: McpClientManager = McpClientManager()
    private var providedRegistry: ToolRegistry? = null
    private var loopGuardPolicy: LoopGuardPolicy = LoopGuardPolicy.NEVER_CONTINUE
    private var learningConfig: LearningConfig? = null
    private var scheduleDir: Path? = null
    private var contextWindowTokens: Int? = null
    private var memoryConfig: MemoryConfig? = null

    /** Test seam only: when set, [build] uses this instead of building an OpenAI-compat embedding
     *  provider from [MemoryConfig.embeddingBaseUrl] — lets tests exercise the probe-success and
     *  probe-failure paths without a real embeddings endpoint. */
    internal var embeddingProviderOverride: EmbeddingProvider? = null

    fun tool(t: Tool): RuntimeBuilder = apply { tools.add(t) }
    fun plugin(p: SophiPlugin): RuntimeBuilder = apply { plugins.add(p) }
    fun confirmationPolicy(policy: ConfirmationPolicy): RuntimeBuilder = apply { confirmationPolicy = policy }
    fun grants(names: Set<String>): RuntimeBuilder = apply { grants = names }
    fun mcpConfig(path: Path): RuntimeBuilder = apply { mcpConfigPath = path }
    fun mcpClientManager(manager: McpClientManager): RuntimeBuilder = apply { mcpClientManager = manager }

    /**
     * Use [registry] instead of a privately created one. Pass your own when something built
     * *before* the runtime needs the same registry — a confirmation policy that inspects tools,
     * or a tool that dispatches to its siblings. Tools registered after [build] are picked up on
     * the next turn, the same way MCP tools connected at runtime are.
     */
    fun toolRegistry(registry: ToolRegistry): RuntimeBuilder = apply { providedRegistry = registry }

    fun loopGuard(policy: LoopGuardPolicy): RuntimeBuilder = apply { loopGuardPolicy = policy }
    fun learning(config: LearningConfig): RuntimeBuilder = apply { learningConfig = config }
    fun schedule(dir: Path): RuntimeBuilder = apply { scheduleDir = dir }
    /**
     * Total context window of [model], in tokens — required before [build]. Sophi compacts the
     * turn's earlier tool rounds once 80% of this is used. There is deliberately no per-model
     * lookup: you pick the model, so you state its window.
     */
    fun contextWindowTokens(tokens: Int): RuntimeBuilder = apply { contextWindowTokens = tokens }

    /**
     * Enables Jane's Theory long-term memory (experimental). [embeddingBaseUrl] is probed once at
     * [build] time; on failure [onWarning] fires and memory stays off for this runtime — it never
     * builds half-on (encoding without recall, or the reverse).
     */
    fun memory(
        embeddingModel: String,
        embeddingBaseUrl: String,
        embeddingApiKey: String? = null,
        embeddingDimensions: Int = 1536,
        onWarning: (String) -> Unit = {}
    ): RuntimeBuilder = apply {
        memoryConfig = MemoryConfig(embeddingModel, embeddingBaseUrl, embeddingApiKey, embeddingDimensions, onWarning)
    }

    fun build(): SophiRuntime {
        val p = requireNotNull(provider) { "provider must be set before calling build()" }
        val window = requireNotNull(contextWindowTokens) {
            "contextWindowTokens must be set before calling build() — pass the total context " +
                "window (in tokens) of the model you configured"
        }
        val registry = (providedRegistry ?: ToolRegistry()).also { r -> tools.forEach { r.register(it) } }
        scheduleDir?.let { dir ->
            registry.register(ScheduleTaskTool(
                TaskStore(dir.resolve("tasks.json")),
                dev.sophi.schedule.store.RunLog(dir.resolve("runs.jsonl"))
            ))
        }
        val mcpServers = mcpConfigPath?.let { McpConfigLoader().load(it).servers.filter { server -> server.enabled } } ?: emptyList()
        runBlocking { mcpClientManager.connect(mcpServers) }.forEach { registry.register(it) }
        val sm = FileSessionManager(sessionsDir)
        val agentConfig = AgentConfig(
            model = model,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt
        )
        val loop = AgentLoop(
            p, registry, sm,
            confirmationPolicy = confirmationPolicy, grants = grants,
            loopGuard = loopGuardPolicy,
            contextWindowTokens = window
        )
        val pluginRegistry = PluginRegistry().also { r -> plugins.forEach { r.register(it) } }

        val learningPlugin = learningConfig?.let { cfg ->
            LearningPlugin(cfg.copy(sessionModel = agentConfig.model), model = agentConfig.model, provider = p, sessionManager = sm).also { plugin ->
                pluginRegistry.register(plugin)
            }
        }
        val learningSection = learningPlugin?.let { it.promptSections(learningConfig!!.scope) }

        val memoryPlugin = memoryConfig?.let { mc ->
            val embeddingProvider = embeddingProviderOverride
                ?: buildOpenAiCompatEmbeddingProvider(mc.embeddingBaseUrl, mc.embeddingApiKey, mc.embeddingModel, mc.embeddingDimensions)
            val probeResult = runBlocking { probeEmbeddingProvider(embeddingProvider) }
            if (probeResult.isFailure) {
                mc.onWarning(
                    "memory: disabled — embeddings endpoint unreachable at ${mc.embeddingBaseUrl} " +
                        "(${mc.embeddingModel}): ${probeResult.exceptionOrNull()?.message ?: "unknown error"}"
                )
                null
            } else {
                val palace = JanesPalace(
                    JanesPalaceConfig(sessionModel = model), p, embeddingProvider, mc.embeddingModel, onWarning = mc.onWarning
                )
                MemoryPlugin(palace).also { pluginRegistry.register(it) }
            }
        }
        val memorySection = if (memoryPlugin != null) MemoryPromptSection.TEXT else null

        val effectiveConfig = agentConfig.copy(
            systemPrompt = (listOf(DefaultPrompt.BASE) +
                listOfNotNull(agentConfig.systemPrompt, learningSection, memorySection)
            ).joinToString("\n\n")
        )

        return SophiRuntime(loop, sm, pluginRegistry, effectiveConfig, mcpClientManager, learningPlugin, registry, p, window, skillsDir, memoryPlugin)
    }
}

private data class MemoryConfig(
    val embeddingModel: String,
    val embeddingBaseUrl: String,
    val embeddingApiKey: String?,
    val embeddingDimensions: Int,
    val onWarning: (String) -> Unit
)
