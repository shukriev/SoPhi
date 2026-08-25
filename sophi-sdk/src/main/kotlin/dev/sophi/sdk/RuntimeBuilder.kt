package dev.sophi.sdk

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.probeEmbeddingProvider
import dev.sophi.ai.providers.buildOpenAiCompatEmbeddingProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentDefinitionLoader
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.LoopGuardPolicy
import dev.sophi.core.agent.SubagentTool
import dev.sophi.core.agent.plan.DecomposeGoalTool
import dev.sophi.core.agent.plan.PlanLog
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
import dev.sophi.skills.SkillInvocationStore
import dev.sophi.skills.SkillRegistry
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class RuntimeBuilder {
    var provider: LLMProvider? = null
    var model: String = "claude-sonnet-4-5"
    var maxTokens: Int = 4096
    var systemPrompt: String? = null
    var sessionsDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "sessions")
    var skillsDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "skills")
    var memoryHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "memory")

    private val tools: MutableList<Tool> = mutableListOf()
    private val plugins: MutableList<SophiPlugin> = mutableListOf()
    private var confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.DENY_ALL
    private var grants: Set<String> = emptySet()
    private var mcpConfigPath: Path? = null
    private var mcpClientManager: McpClientManager = McpClientManager()
    private var providedRegistry: ToolRegistry? = null
    private var loopGuardPolicy: LoopGuardPolicy = LoopGuardPolicy.NEVER_CONTINUE
    private var learningConfig: LearningConfig? = null
    private var learningEmbeddingProvider: EmbeddingProvider? = null
    private var scheduleDir: Path? = null
    private var contextWindowTokens: Int? = null
    private var memoryConfig: MemoryConfig? = null
    private var agentsDirConfig: AgentsDirConfig? = null
    private var builtinToolsConfig: BuiltinToolsConfig? = null
    private var subagentDelegationEnabled: Boolean = false
    private var goalDecompositionPlansDir: Path? = null
    private var skillToolsConfig: SkillToolsConfig? = null

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
    fun learning(config: LearningConfig, embeddingProvider: EmbeddingProvider? = null): RuntimeBuilder =
        apply { learningConfig = config; learningEmbeddingProvider = embeddingProvider }
    fun schedule(dir: Path): RuntimeBuilder = apply { scheduleDir = dir }

    /**
     * Loads AgentDefinitions from [dir] (created if missing) for this runtime's scheduleEngine()
     * calls to enforce as an allowlist. [onWarning] fires — and definitions stay empty — if any
     * file in [dir] fails to parse; never throws (see AgentDefinitionLoader.loadOrWarn).
     */
    fun agentsDir(dir: Path, onWarning: (String) -> Unit = { System.err.println(it) }): RuntimeBuilder =
        apply { agentsDirConfig = AgentsDirConfig(dir, onWarning) }

    /** Registers the standard file/shell/search/date tool set — see [buildBuiltinTools]. */
    fun builtinTools(root: Path, braveApiKey: String? = null): RuntimeBuilder =
        apply { builtinToolsConfig = BuiltinToolsConfig(root, braveApiKey) }

    /**
     * Registers a `delegate_to_subagent` tool built from whatever [agentsDir] loaded — a no-op
     * if [agentsDir] was never called or found no definitions, matching [SubagentTool]'s own
     * "definitions is empty" guard. Safe to call without [agentsDir]; just inert.
     */
    fun subagentDelegation(): RuntimeBuilder = apply { subagentDelegationEnabled = true }

    /** Registers a `decompose_goal` tool; every plan version is logged under [plansDir] ([PlanLog] creates it if missing). */
    fun goalDecomposition(plansDir: Path): RuntimeBuilder = apply { goalDecompositionPlansDir = plansDir }

    /**
     * Registers `skill` (only when [globalDir]/[projectDir] together yield at least one skill —
     * an empty skill set advertising itself as a tool is just noise), and unconditionally
     * `install_skill`/`write_skill`.
     */
    fun skillTools(globalDir: Path, projectDir: Path? = null): RuntimeBuilder =
        apply { skillToolsConfig = SkillToolsConfig(globalDir, projectDir) }
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
     *
     * [embeddingProvider] is a test seam: when set, [build] uses it instead of building an
     * OpenAI-compat embedding provider from [embeddingBaseUrl] — lets tests exercise the
     * probe-success and probe-failure paths without a real embeddings endpoint.
     */
    fun memory(
        embeddingModel: String,
        embeddingBaseUrl: String,
        embeddingApiKey: String? = null,
        embeddingDimensions: Int = 1536,
        onWarning: (String) -> Unit = {},
        embeddingProvider: EmbeddingProvider? = null
    ): RuntimeBuilder = apply {
        memoryConfig = MemoryConfig(embeddingModel, embeddingBaseUrl, embeddingApiKey, embeddingDimensions, onWarning, embeddingProvider)
    }

    fun build(): SophiRuntime {
        val p = requireNotNull(provider) { "provider must be set before calling build()" }
        val window = requireNotNull(contextWindowTokens) {
            "contextWindowTokens must be set before calling build() — pass the total context " +
                "window (in tokens) of the model you configured"
        }
        val agentDefinitions = agentsDirConfig?.let { cfg ->
            AgentDefinitionLoader().loadOrWarn(cfg.dir, cfg.onWarning)
        } ?: emptyList()
        val registry = (providedRegistry ?: ToolRegistry()).also { r -> tools.forEach { r.register(it) } }
        builtinToolsConfig?.let { cfg -> buildBuiltinTools(cfg.root, cfg.braveApiKey).forEach { registry.register(it) } }
        skillToolsConfig?.let { cfg ->
            val skillRegistry = SkillRegistry.load(cfg.globalDir, cfg.projectDir)
            if (skillRegistry.all().isNotEmpty()) registry.register(SkillTool(skillRegistry))
            registry.register(InstallSkillTool())
            registry.register(WriteSkillTool())
        }
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
        SkillInvocationPlugin(SkillInvocationStore(skillsDir.resolve(".invocations.jsonl")))
            .also { pluginRegistry.register(it) }

        val learningPlugin = learningConfig?.let { cfg ->
            LearningPlugin(cfg.copy(sessionModel = agentConfig.model), model = agentConfig.model, provider = p,
                sessionManager = sm, embeddingProvider = learningEmbeddingProvider).also { plugin ->
                pluginRegistry.register(plugin)
            }
        }
        val learningSection = learningPlugin?.let { it.promptSections(learningConfig!!.scope) }

        val memoryPlugin = memoryConfig?.let { mc ->
            val embeddingProvider = mc.embeddingProvider
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
                    JanesPalaceConfig(home = memoryHome, sessionModel = model, autoPurgeEnabled = JanesPalaceConfig.autoPurgeEnabledFromEnv()),
                    p, embeddingProvider, mc.embeddingModel, onWarning = mc.onWarning
                )
                MemoryPlugin(palace).also { pluginRegistry.register(it) }
            }
        }
        val memorySection = if (memoryPlugin != null) MemoryPromptSection.TEXT else null

        val effectiveConfig = agentConfig.copy(
            systemPrompt = listOfNotNull(
                DefaultPrompt.BASE, agentConfig.systemPrompt, learningSection, memorySection
            ).joinToString("\n\n")
        )

        if (subagentDelegationEnabled && agentDefinitions.isNotEmpty()) {
            registry.register(
                SubagentTool(
                    definitions = agentDefinitions,
                    provider = p,
                    fullRegistry = registry,
                    sessionManager = sm,
                    parentConfig = effectiveConfig,
                    contextWindowTokens = window,
                    confirmationPolicy = confirmationPolicy
                )
            )
        }
        goalDecompositionPlansDir?.let { dir ->
            registry.register(
                DecomposeGoalTool(
                    provider = p,
                    fullRegistry = registry,
                    sessionManager = sm,
                    parentConfig = effectiveConfig,
                    contextWindowTokens = window,
                    planLog = PlanLog(dir),
                    confirmationPolicy = confirmationPolicy
                )
            )
        }

        return SophiRuntime(loop, sm, pluginRegistry, effectiveConfig, mcpClientManager, learningPlugin, registry, p, window, skillsDir, memoryPlugin, agentDefinitions)
    }
}

private data class AgentsDirConfig(val dir: Path, val onWarning: (String) -> Unit)

private data class BuiltinToolsConfig(val root: Path, val braveApiKey: String?)

private data class SkillToolsConfig(val globalDir: Path, val projectDir: Path?)

private data class MemoryConfig(
    val embeddingModel: String,
    val embeddingBaseUrl: String,
    val embeddingApiKey: String?,
    val embeddingDimensions: Int,
    val onWarning: (String) -> Unit,
    val embeddingProvider: EmbeddingProvider? = null
)
