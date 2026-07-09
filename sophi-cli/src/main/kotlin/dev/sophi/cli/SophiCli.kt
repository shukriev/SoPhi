package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.ai.providers.BraveSearchProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentDefinitionLoader
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.SubagentTool
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.EditTool
import dev.sophi.core.tools.FetchUrlTool
import dev.sophi.core.tools.FileReadTool
import dev.sophi.core.tools.FileWriteTool
import dev.sophi.core.tools.GlobTool
import dev.sophi.core.tools.GrepTool
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.tools.WebSearchTool
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.turnEventBridge
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import dev.sophi.learning.ToolReliabilitySection
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.config.McpConfigLoader
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import java.nio.file.Path

class SophiCli : CliktCommand(name = "sophi", help = "Sophi — Kotlin agent harness") {

    private val sessionId: String? by option(
        "--session", "-s",
        help = "Session ID to resume (omit to start a new session)"
    )
    private val model: String by option(
        "--model", "-m",
        help = "LLM model name (for --provider openai-compat, always set this explicitly — e.g. qwen2.5:7b)"
    ).default("claude-3-5-sonnet-20241022")
    private val providerType: String by option(
        "--provider",
        help = "LLM provider: 'claude' (default) or 'openai-compat' (Ollama, vLLM, or any OpenAI-compatible server)"
    ).default("claude")
    private val baseUrl: String? by option(
        "--base-url",
        help = "Base URL for --provider openai-compat, e.g. http://localhost:11434/v1 (Ollama) or http://localhost:8000/v1 (vLLM)"
    )
    private val apiKeyOption: String? by option(
        "--api-key",
        help = "API key (falls back to ANTHROPIC_API_KEY for --provider claude; omit for no-auth local servers)"
    )
    private val sessionsDirStr: String by option(
        "--sessions-dir",
        help = "Directory for session JSONL files"
    ).default("${System.getProperty("user.home")}/.sophi/sessions")
    private val agentsDirStr: String by option(
        "--agents-dir",
        help = "Directory of subagent definition Markdown files"
    ).default("${System.getProperty("user.home")}/.sophi/agents")
    private val systemPrompt: String? by option(
        "--system",
        help = "System prompt injected into every turn"
    )
    private val braveApiKeyOption: String? by option(
        "--brave-api-key",
        help = "Brave Search API key for the web_search tool (falls back to BRAVE_SEARCH_API_KEY; omit to disable web_search)"
    )
    private val mcpConfigPathStr: String by option(
        "--mcp-config",
        help = "Path to an MCP server config file (default: .sophi/mcp.json in the working directory)"
    ).default(".sophi/mcp.json")

    override fun run() = runBlocking {
        if (currentContext.invokedSubcommand != null) return@runBlocking
        val provider = buildProvider(providerType, apiKeyOption, baseUrl, model)
        val sessionManager = FileSessionManager(Path.of(sessionsDirStr))
        val session = sessionId?.let { sessionManager.load(it) } ?: sessionManager.create()

        // Learning: capture tool outcomes and inject a reliability section into the system prompt.
        val learningConfig = LearningConfig()
        val learningPlugin = LearningPlugin(learningConfig, model = model)
        val pluginRegistry = PluginRegistry().register(learningPlugin)
        val bridge = pluginRegistry.turnEventBridge(session.id)
        val reliabilitySection =
            ToolReliabilitySection(learningPlugin.toolStats, learningConfig).render(learningConfig.scope)
        val effectiveSystemPrompt =
            listOfNotNull(systemPrompt, reliabilitySection).takeIf { it.isNotEmpty() }?.joinToString("\n\n")

        val config = AgentConfig(model = model, systemPrompt = effectiveSystemPrompt)
        runCatching { sessionManager.saveConfigSnapshot(session.id, model, config.systemPrompt) }
        val mordantTerminal = Terminal()
        val confirmationPolicy = TerminalConfirmationPolicy(mordantTerminal)

        val agentsDir = Path.of(agentsDirStr).also { it.createDirectories() }
        val agentDefinitions = AgentDefinitionLoader().load(agentsDir)

        val registry = ToolRegistry()
        buildBuiltinTools(braveApiKeyOption).forEach { registry.register(it) }
        val mcpClientManager = McpClientManager()
        val mcpConfigPath = Path.of(mcpConfigPathStr)
        if (mcpConfigPath.exists()) {
            val mcpConfig = McpConfigLoader().load(mcpConfigPath)
            mcpClientManager.connect(mcpConfig.servers).forEach { registry.register(it) }
        }
        if (agentDefinitions.isNotEmpty()) {
            registry.register(
                SubagentTool(
                    definitions = agentDefinitions,
                    provider = provider,
                    fullRegistry = registry,
                    sessionManager = sessionManager,
                    parentSessionId = session.id,
                    parentConfig = config,
                    confirmationPolicy = confirmationPolicy
                )
            )
        }

        val loop = AgentLoop(
            provider,
            registry,
            sessionManager,
            confirmationPolicy = confirmationPolicy
        )
        val compactor = ContextCompactor(provider)
        val sophiTerminal = SophiTerminal.create()

        mordantTerminal.println(TextColors.cyan("Sophi — session ${session.id}"))
        mordantTerminal.println("Type 'exit' or 'quit' to end. Commands: /list /branch /checkout /compact\n")

        val slashHandler = SlashHandler(sessionManager, compactor, config) { mordantTerminal.println(it) }
        val inputSource: InputSource =
            if (sophiTerminal.isInteractive) JLineInputSource(sophiTerminal) else LegacyReadLineInputSource()
        val liveRegionSink: Appendable = if (sophiTerminal.isInteractive) {
            java.io.PrintWriter(System.out, true)
        } else {
            object : Appendable {
                override fun append(csq: CharSequence?): Appendable = this
                override fun append(csq: CharSequence?, start: Int, end: Int): Appendable = this
                override fun append(c: Char): Appendable = this
            }
        }
        val liveRegion = LiveRegion(liveRegionSink) { mordantTerminal.info.width }
        val turnController = TurnController(
            loop, config, inputSource, liveRegion, onEvent = bridge,
            onTurnSettled = { error ->
                // Learning must never break a turn: dispatch is best-effort.
                runCatching {
                    if (error != null) {
                        pluginRegistry.dispatch(HookPoint.ON_ERROR, HookContext(session.id, error = error))
                    } else {
                        pluginRegistry.dispatch(HookPoint.AFTER_TURN, HookContext(session.id))
                    }
                }
            }
        ) {
            mordantTerminal.println(it)
        }
        val engine = TuiEngine(turnController, slashHandler, inputSource)

        try {
            engine.run(session)
        } finally {
            // TuiEngine.run returns on both exit paths (exit/quit and EOF); record the outcome once.
            runCatching { learningPlugin.recordSessionEnd(session.id) }
            sophiTerminal.close()
            mcpClientManager.close()
        }
        mordantTerminal.println(TextColors.cyan("\nSession ${session.id} ended."))
    }
}

private class LegacyReadLineInputSource : InputSource {
    override suspend fun readLine(): String? = kotlin.io.readLine()
    override suspend fun awaitEsc() {
        kotlinx.coroutines.delay(Long.MAX_VALUE)
    }
}

internal fun buildBuiltinTools(braveApiKeyOption: String?): List<Tool> {
    val tools = mutableListOf<Tool>(
        FileReadTool(), FileWriteTool(), GrepTool(), GlobTool(), EditTool(), BashTool(), FetchUrlTool()
    )
    val braveApiKey = braveApiKeyOption ?: System.getenv("BRAVE_SEARCH_API_KEY")
    if (braveApiKey != null) {
        tools.add(WebSearchTool(BraveSearchProvider(braveApiKey)))
    }
    return tools
}
