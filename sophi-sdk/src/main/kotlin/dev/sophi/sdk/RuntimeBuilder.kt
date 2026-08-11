package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentLoop
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
    /** Remembered on the built SophiRuntime for mcpServers()/addOrUpdateMcpServer()/etc. —
     *  setting this alone has no side effect; it does not trigger build()'s auto-connect
     *  (see mcpConfig(path) for that). */
    var mcpConfigPath: Path? = null

    private val tools: MutableList<Tool> = mutableListOf()
    private val plugins: MutableList<SophiPlugin> = mutableListOf()
    private var confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.DENY_ALL
    private var grants: Set<String> = emptySet()
    private var autoConnectMcpConfigPath: Path? = null
    private var mcpClientManager: McpClientManager = McpClientManager()
    private var learningConfig: LearningConfig? = null
    private var scheduleDir: Path? = null
    private var contextWindowTokens: Int? = null

    fun tool(t: Tool): RuntimeBuilder = apply { tools.add(t) }
    fun plugin(p: SophiPlugin): RuntimeBuilder = apply { plugins.add(p) }
    fun confirmationPolicy(policy: ConfirmationPolicy): RuntimeBuilder = apply { confirmationPolicy = policy }
    fun grants(names: Set<String>): RuntimeBuilder = apply { grants = names }
    fun mcpConfig(path: Path): RuntimeBuilder = apply { autoConnectMcpConfigPath = path }
    fun mcpClientManager(manager: McpClientManager): RuntimeBuilder = apply { mcpClientManager = manager }
    fun learning(config: LearningConfig): RuntimeBuilder = apply { learningConfig = config }
    fun schedule(dir: Path): RuntimeBuilder = apply { scheduleDir = dir }
    /**
     * Total context window of [model], in tokens — required before [build]. Sophi compacts the
     * turn's earlier tool rounds once 80% of this is used. There is deliberately no per-model
     * lookup: you pick the model, so you state its window.
     */
    fun contextWindowTokens(tokens: Int): RuntimeBuilder = apply { contextWindowTokens = tokens }

    fun build(): SophiRuntime {
        val p = requireNotNull(provider) { "provider must be set before calling build()" }
        val window = requireNotNull(contextWindowTokens) {
            "contextWindowTokens must be set before calling build() — pass the total context " +
                "window (in tokens) of the model you configured"
        }
        val registry = ToolRegistry().also { r -> tools.forEach { r.register(it) } }
        scheduleDir?.let { dir ->
            registry.register(ScheduleTaskTool(
                TaskStore(dir.resolve("tasks.json")),
                dev.sophi.schedule.store.RunLog(dir.resolve("runs.jsonl"))
            ))
        }
        val mcpServersToAutoConnect = autoConnectMcpConfigPath
            ?.let { McpConfigLoader().load(it).servers.filter { server -> server.enabled } }
            ?: emptyList()
        runBlocking { mcpClientManager.connect(mcpServersToAutoConnect) }.forEach { registry.register(it) }
        val sm = FileSessionManager(sessionsDir)
        val agentConfig = AgentConfig(
            model = model,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt
        )
        val loop = AgentLoop(
            p, registry, sm,
            confirmationPolicy = confirmationPolicy, grants = grants,
            contextWindowTokens = window
        )
        val pluginRegistry = PluginRegistry().also { r -> plugins.forEach { r.register(it) } }

        val learningPlugin = learningConfig?.let { cfg ->
            LearningPlugin(cfg.copy(sessionModel = agentConfig.model), model = agentConfig.model, provider = p, sessionManager = sm).also { plugin ->
                pluginRegistry.register(plugin)
            }
        }
        val effectiveConfig = learningPlugin?.let { plugin ->
            val section = plugin.promptSections(learningConfig!!.scope)
            if (section != null)
                agentConfig.copy(
                    systemPrompt = listOfNotNull(agentConfig.systemPrompt, section).joinToString("\n\n")
                )
            else agentConfig
        } ?: agentConfig

        return SophiRuntime(loop, sm, pluginRegistry, effectiveConfig, mcpClientManager, learningPlugin, registry, p, window, skillsDir, mcpConfigPath)
    }
}
