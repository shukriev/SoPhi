package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
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
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.config.McpConfigLoader
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import java.nio.file.Path

class SophiCli : CliktCommand(name = "sophi", help = "Sophi — Kotlin agent harness", invokeWithoutSubcommand = true) {

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
    private val llmTimeoutSeconds: Long by option(
        "--llm-timeout-seconds",
        help = "Client-side request timeout for --provider openai-compat. Raise this for local " +
            "reasoning models, which can spend well over a minute on hidden chain-of-thought " +
            "before producing any output."
    ).long().default(60)
    private val llmMaxRetries: Int by option(
        "--llm-max-retries",
        help = "Retries for --provider openai-compat on request failure. Each retry is subject " +
            "to the full --llm-timeout-seconds, so worst-case latency before an error surfaces " +
            "is timeout * (retries + 1) — lower this (e.g. to 0) for a slow model that's " +
            "consistently near the timeout, rather than silently waiting that multiple."
    ).int().default(2)
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
    private val memoryEnabled: Boolean by option(
        "--memory",
        help = "Enable Jane's Theory long-term memory (experimental). Requires --embedding-model " +
            "and an OpenAI-compatible embeddings endpoint (Ollama works: nomic-embed-text)."
    ).flag(default = false)
    private val embeddingModel: String? by option(
        "--embedding-model",
        help = "Embedding model name for --memory, e.g. nomic-embed-text (Ollama) or text-embedding-3-small"
    )
    private val embeddingBaseUrl: String? by option(
        "--embedding-base-url",
        help = "Embeddings endpoint base URL (defaults to --base-url; e.g. http://localhost:11434/v1)"
    )
    private val embeddingDimensions: Int by option(
        "--embedding-dimensions",
        help = "Embedding vector dimensions (768 for nomic-embed-text, 1536 for text-embedding-3-small)"
    ).int().default(1536)

    override fun run() = runBlocking {
        if (currentContext.invokedSubcommand != null) return@runBlocking
        val provider = buildProvider(providerType, apiKeyOption, baseUrl, model, llmTimeoutSeconds, llmMaxRetries)
        val sessionManager = FileSessionManager(Path.of(sessionsDirStr))
        val session = sessionId?.let { sessionManager.load(it) } ?: sessionManager.create()
        val mordantTerminal = Terminal()

        // Learning: capture tool outcomes and inject reliability + lessons sections into the system prompt.
        val learningConfig = LearningConfig(sessionModel = model)
        val learningPlugin = LearningPlugin(learningConfig, model = model, provider = provider, sessionManager = sessionManager)
        val pluginRegistry = PluginRegistry().register(learningPlugin)

        // Memory (Jane's Theory): per-turn recall via ContextContributor, async encoding on AFTER_TURN.
        val memoryPlugin: dev.sophi.memory.MemoryPlugin? = if (memoryEnabled) {
            val embBase = embeddingBaseUrl ?: baseUrl
            val embModel = embeddingModel
            if (embBase == null || embModel == null) {
                mordantTerminal.println(TextColors.yellow(
                    "memory: disabled — --memory needs --embedding-model and --embedding-base-url (or --base-url)"))
                null
            } else {
                val embProvider = dev.sophi.ai.providers.buildOpenAiCompatEmbeddingProvider(
                    embBase, apiKeyOption, embModel, embeddingDimensions)
                // Spec §6: memory must never fail silently (cognitive-prosthetic honesty).
                // Probe the endpoint once; if unreachable, disable memory with ONE visible warning.
                val probeResult = runCatching { embProvider.embed(listOf("ping")) }
                if (probeResult.isFailure) {
                    val error = probeResult.exceptionOrNull()?.message ?: "unknown error"
                    mordantTerminal.println(TextColors.yellow(
                        "memory: disabled — embeddings endpoint unreachable at $embBase ($embModel): $error"))
                    null
                } else {
                    val palace = dev.sophi.memory.jane.JanesPalace(
                        dev.sophi.memory.jane.JanesPalaceConfig(sessionModel = model),
                        provider, embProvider, embModel)
                    dev.sophi.memory.MemoryPlugin(palace)
                }
            }
        } else null
        memoryPlugin?.let { pluginRegistry.register(it) }

        val bridge = pluginRegistry.turnEventBridge(session.id)
        val effectiveSystemPrompt =
            listOfNotNull(
                systemPrompt,
                learningPlugin.promptSections(learningConfig.scope),
                if (memoryPlugin != null) dev.sophi.memory.MemoryPromptSection.TEXT else null
            ).takeIf { it.isNotEmpty() }?.joinToString("\n\n")

        val config = AgentConfig(model = model, systemPrompt = effectiveSystemPrompt)
        runCatching { sessionManager.saveConfigSnapshot(session.id, model, config.systemPrompt) }
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
        mordantTerminal.println("Type 'exit' or 'quit' to end. Commands: /list /branch /checkout /compact /good /bad\n")

        val slashHandler = SlashHandler(sessionManager, compactor, config, learningPlugin) { mordantTerminal.println(it) }
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
            contextProvider = { sess, input ->
                pluginRegistry.collectContext(sess.id, input).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
            },
            onTurnSettled = { userInput, assistantReply, error ->
                // Learning/memory must never break a turn: dispatch is best-effort.
                runCatching {
                    if (error != null) {
                        pluginRegistry.dispatch(HookPoint.ON_ERROR, HookContext(session.id, error = error))
                    } else {
                        pluginRegistry.dispatch(HookPoint.AFTER_TURN,
                            HookContext(session.id, userInput = userInput, assistantReply = assistantReply))
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
            runCatching {
                memoryPlugin?.let { mp ->
                    mp.consolidateIfDue()?.let { report ->
                        if (report.total > 0) mordantTerminal.println(
                            "memory: consolidated (merged=${report.merged} strengthened=${report.strengthened} " +
                            "compressed=${report.compressed} pruned=${report.pruned} purged=${report.purged})")
                    }
                    mp.close()
                }
            }
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
