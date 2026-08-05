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
import dev.sophi.core.tools.AutoModeConfirmationPolicy
import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.EditTool
import dev.sophi.core.tools.FetchUrlTool
import dev.sophi.core.tools.FileReadTool
import dev.sophi.core.tools.FileWriteTool
import dev.sophi.core.tools.GetCurrentDateTimeTool
import dev.sophi.core.tools.GlobTool
import dev.sophi.core.tools.GrepTool
import dev.sophi.core.tools.LlmRiskClassifier
import dev.sophi.core.tools.RiskClassifier
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToggleableConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.tools.WebSearchTool
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.calendar.tools.CreateCalendarEventTool
import dev.sophi.calendar.tools.DeleteCalendarEventTool
import dev.sophi.calendar.tools.GetCalendarEventTool
import dev.sophi.calendar.tools.ListCalendarEventsTool
import dev.sophi.calendar.tools.ListCalendarsTool
import dev.sophi.calendar.tools.UpdateCalendarEventTool
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.turnEventBridge
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.config.McpConfigLoader
import dev.sophi.skills.SkillRegistry
import dev.sophi.schedule.store.TaskStore
import dev.sophi.schedule.tools.ScheduleTaskTool
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds
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
    private val contextWindowTokens: Int by option(
        "--context-window-tokens",
        help = "Total context window of --model, in tokens (e.g. 200000 for Claude Sonnet, or " +
            "whatever your local model was built with). Sophi summarises this turn's earlier " +
            "tool rounds once 80% of this is used, instead of capping the number of rounds. " +
            "There is no per-model registry — you pick the model, so you say what its window is."
    ).int().default(200_000)
    private val providerType: String by option(
        "--provider",
        help = "LLM provider: 'claude' (default) or 'openai-compat' (Ollama, vLLM, or any OpenAI-compatible server)"
    ).default("claude")
    private val maxTokens: Int by option(
        "--max-tokens",
        help = "Max completion tokens per turn. Raise this for local reasoning models — hidden " +
            "chain-of-thought counts against this budget, so a low value can exhaust it before " +
            "the model ever emits an answer or tool call (finish_reason=length, no visible output)."
    ).int().default(4096)
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
    private val scheduleDirStr: String by option(
        "--schedule-dir",
        help = "Directory for scheduled/goal task definitions and run history"
    ).default("${System.getProperty("user.home")}/.sophi/schedule")
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
    private val tokenViewKey: String by option(
        "--token-view-key",
        help = "Keyboard shortcut to toggle token visibility during streaming (default: T)"
    ).default("T")
    private val autoExitTokenView: Boolean by option(
        "--auto-exit-token-view",
        help = "Automatically exit token view when LLM finishes (default: true)"
    ).flag(default = true)
    private val autoMode: Boolean by option(
        "--auto",
        help = "Start with auto mode enabled: low-risk tool calls (per rule + LLM classifier) run " +
            "without a confirmation prompt. Toggle anytime with /auto."
    ).flag(default = false)
    private val godMode: Boolean by option(
        "--god-mode",
        help = "Skip the LLM classifier entirely: trust only each tool's own rule checks. Prompts " +
            "only for calls a rule explicitly flags HIGH_RISK; everything else (including anything " +
            "a rule has no opinion on) runs unattended for the whole session. Fixed once set — no " +
            "/auto toggle while in this mode. Overrides --auto if both are passed."
    ).flag(default = false)

    override fun run() = runBlocking {
        if (currentContext.invokedSubcommand != null) return@runBlocking
        val provider = buildProvider(providerType, apiKeyOption, baseUrl, model, llmTimeoutSeconds, llmMaxRetries)
        val sessionManager = FileSessionManager(Path.of(sessionsDirStr))
        val session = sessionId?.let { sessionManager.load(it) } ?: sessionManager.create()
        val mordantTerminal = Terminal()
        val sophiTerminal = SophiTerminal.create()
        val inputSource: InputSource =
            if (sophiTerminal.isInteractive) JLineInputSource(sophiTerminal) else LegacyReadLineInputSource()

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
                val probeResult = dev.sophi.ai.api.probeEmbeddingProvider(embProvider)
                if (probeResult.isFailure) {
                    val error = probeResult.exceptionOrNull()?.message ?: "unknown error"
                    mordantTerminal.println(TextColors.yellow(
                        "memory: disabled — embeddings endpoint unreachable at $embBase ($embModel): $error"))
                    null
                } else {
                    val palace = dev.sophi.memory.jane.JanesPalace(
                        dev.sophi.memory.jane.JanesPalaceConfig(sessionModel = model),
                        provider, embProvider, embModel,
                        // Encoding runs fire-and-forget on AFTER_TURN (MemoryPlugin), so this
                        // warning can arrive at any time relative to the next readLine() prompt —
                        // printAbove keeps it from landing glued onto that prompt's line.
                        onWarning = { msg ->
                            if (sophiTerminal.isInteractive) sophiTerminal.printAbove(TextColors.yellow(msg).toString())
                            else mordantTerminal.println(TextColors.yellow(msg))
                        })
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

        val config = AgentConfig(model = model, maxTokens = maxTokens, systemPrompt = effectiveSystemPrompt)
        runCatching { sessionManager.saveConfigSnapshot(session.id, model, config.systemPrompt) }
        val registry = ToolRegistry()
        val manualConfirmationPolicy = TerminalConfirmationPolicy(mordantTerminal, inputSource)
        val toggleableConfirmationPolicy: ToggleableConfirmationPolicy? = if (godMode) null else {
            val autoModePolicy = AutoModeConfirmationPolicy(
                registry,
                LlmRiskClassifier(provider, model, maxTokens = maxTokens, timeout = llmTimeoutSeconds.seconds),
                manualConfirmationPolicy
            )
            ToggleableConfirmationPolicy(autoModePolicy, manualConfirmationPolicy, autoModeEnabled = autoMode)
        }
        val confirmationPolicy: ConfirmationPolicy = toggleableConfirmationPolicy
            ?: AutoModeConfirmationPolicy(registry, RiskClassifier.ALWAYS_LOW_RISK, manualConfirmationPolicy)
        val loopGuardPolicy = TerminalLoopGuardPolicy(mordantTerminal, inputSource)

        val agentsDir = Path.of(agentsDirStr).also { it.createDirectories() }
        val agentDefinitions = AgentDefinitionLoader().load(agentsDir)

        val skillRegistry = SkillRegistry.load(
            globalDir = Path.of(System.getProperty("user.home"), ".sophi", "skills"),
            projectDir = Path.of(".sophi", "skills")
        )

        buildBuiltinTools(braveApiKeyOption).forEach { registry.register(it) }
        registry.register(ScheduleTaskTool(
            TaskStore(Path.of(scheduleDirStr).resolve("tasks.json")),
            dev.sophi.schedule.store.RunLog(Path.of(scheduleDirStr).resolve("runs.jsonl"))
        ))
        val calendarProvider = buildCalendarProvider()
        registry.register(CreateCalendarEventTool(calendarProvider))
        registry.register(ListCalendarEventsTool(calendarProvider))
        registry.register(GetCalendarEventTool(calendarProvider))
        registry.register(UpdateCalendarEventTool(calendarProvider))
        registry.register(DeleteCalendarEventTool(calendarProvider))
        registry.register(ListCalendarsTool(calendarProvider))
        val mcpClientManager = McpClientManager()
        val mcpConfigPath = Path.of(mcpConfigPathStr)
        if (mcpConfigPath.exists()) {
            val mcpConfig = McpConfigLoader().load(mcpConfigPath)
            mcpClientManager.connect(mcpConfig.servers).forEach { registry.register(it) }
        }
        if (skillRegistry.all().isNotEmpty()) {
            registry.register(SkillTool(skillRegistry))
        }
        registry.register(InstallSkillTool())
        if (agentDefinitions.isNotEmpty()) {
            registry.register(
                SubagentTool(
                    definitions = agentDefinitions,
                    provider = provider,
                    fullRegistry = registry,
                    sessionManager = sessionManager,
                    parentSessionId = session.id,
                    parentConfig = config,
                    contextWindowTokens = contextWindowTokens,
                    confirmationPolicy = confirmationPolicy
                )
            )
        }
        registry.register(
            dev.sophi.core.agent.plan.DecomposeGoalTool(
                provider = provider,
                fullRegistry = registry,
                sessionManager = sessionManager,
                parentSessionId = session.id,
                parentConfig = config,
                contextWindowTokens = contextWindowTokens,
                confirmationPolicy = confirmationPolicy
            )
        )

        val loop = AgentLoop(
            provider,
            registry,
            sessionManager,
            confirmationPolicy = confirmationPolicy,
            loopGuard = loopGuardPolicy,
            contextWindowTokens = contextWindowTokens
        )
        val compactor = ContextCompactor(provider)

        mordantTerminal.println(TextColors.cyan("Sophi — session ${session.id}"))
        mordantTerminal.println(
            "Type 'exit' or 'quit' to end. Commands: /list /branch /checkout /compact /good /bad " +
                "/schedule /calendar /feedback /lessons /memory /skill /plan /auto\n"
        )
        if (godMode) {
            if (autoMode) {
                mordantTerminal.println(
                    TextColors.yellow("--auto ignored: --god-mode is more permissive and already active")
                )
            }
            mordantTerminal.println(
                TextColors.red(
                    "God mode is on — only rule-flagged HIGH_RISK calls will prompt. " +
                        "Everything else runs unattended."
                )
            )
        } else if (autoMode) {
            mordantTerminal.println(
                TextColors.cyan("Auto mode is on — low-risk tool calls run without asking. Toggle with /auto.")
            )
        }

        val slashHandler = SlashHandler(
            sessionManager, compactor, config, learningPlugin,
            scheduleDir = Path.of(scheduleDirStr), memoryPlugin = memoryPlugin,
            skillRegistry = skillRegistry,
            provider = provider, calendarProvider = calendarProvider, confirmationPolicy = confirmationPolicy,
            autoModeToggle = toggleableConfirmationPolicy,
            toolRegistry = registry,
            contextWindowTokens = contextWindowTokens
        ) { mordantTerminal.println(it) }
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
        if (tokenViewKey.length != 1) {
            mordantTerminal.println(TextColors.yellow(
                "token view: --token-view-key must be a single character, got \"$tokenViewKey\" — using default 'T'"))
        }
        val turnController = TurnController(
            loop, config, inputSource, liveRegion, onEvent = bridge,
            tokenViewKey = tokenViewKey.singleOrNull() ?: 'T',
            autoExitTokenView = autoExitTokenView,
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
    override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit) {
        kotlinx.coroutines.delay(Long.MAX_VALUE)
    }
    // No raw-mode reader loop is running in this (non-interactive) mode, so a plain, independent
    // stdin read is safe here — unlike JLineInputSource, there's nothing for it to race against.
    override suspend fun awaitYesNo(): Boolean =
        kotlin.io.readlnOrNull()?.trim()?.equals("y", ignoreCase = true) == true
}

internal fun buildBuiltinTools(braveApiKeyOption: String?): List<Tool> {
    val tools = mutableListOf<Tool>(
        FileReadTool(), FileWriteTool(), GrepTool(), GlobTool(), EditTool(), BashTool(), FetchUrlTool(),
        GetCurrentDateTimeTool(), dev.sophi.core.tools.RunClaudeCodeTool()
    )
    val braveApiKey = braveApiKeyOption ?: System.getenv("BRAVE_SEARCH_API_KEY")
    if (braveApiKey != null) {
        tools.add(WebSearchTool(BraveSearchProvider(braveApiKey)))
    }
    return tools
}
