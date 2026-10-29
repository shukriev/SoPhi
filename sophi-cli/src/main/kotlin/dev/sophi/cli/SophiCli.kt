package dev.sophi.cli

import dev.sophi.core.tools.DEFAULT_DOCKER_IMAGE
import dev.sophi.core.tools.SandboxMode
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentDefinitionLoader
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.SubagentTool
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.AutoModeConfirmationPolicy
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.LlmRiskClassifier
import dev.sophi.core.tools.RiskClassifier
import dev.sophi.core.tools.ToggleableConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.hub.HubClient
import dev.sophi.hub.HubCommand
import dev.sophi.hub.HubEvent
import dev.sophi.hub.toHubEvent
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds
import java.nio.file.Path

internal data class EmbeddingChoice(val model: String?, val baseUrl: String?, val dimensions: Int)

/**
 * The embeddings --memory uses: the flags as given, or, when neither --embedding-model nor
 * --embedding-base-url was passed, the nomic-embed model the local server already has (768 dims).
 */
internal fun chooseEmbedding(
    memoryEnabled: Boolean, model: String?, baseUrl: String?, dimensions: Int?, resolved: ResolvedProvider
): EmbeddingChoice =
    if (memoryEnabled && model == null && baseUrl == null && resolved.embeddingModel != null)
        EmbeddingChoice(resolved.embeddingModel, resolved.baseUrl, dimensions ?: 768)
    else EmbeddingChoice(model, baseUrl, dimensions ?: 1536)

class SophiCli(
    private val settingsFile: Path = DEFAULT_SETTINGS_FILE
) : CliktCommand(name = "sophi", help = "Sophi — Kotlin agent harness", invokeWithoutSubcommand = true) {
    private val settings = CliSettingsStore(settingsFile) { System.err.println(TextColors.yellow(it)) }
    init { readSettingsFrom(settings) }

    private val sessionId: String? by option(
        "--session", "-s",
        help = "Session ID to resume (omit to start a new session)"
    )
    private val providerOptions by ProviderOptions()
    private val maxTokens: Int by option(
        "--max-tokens",
        help = "Max completion tokens per turn. Raise this for local reasoning models — hidden " +
            "chain-of-thought counts against this budget, so a low value can exhaust it before " +
            "the model ever emits an answer or tool call (finish_reason=length, no visible output)."
    ).int().default(4096)
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
    private val plansDirStr: String by option(
        "--plans-dir",
        help = "Directory for /plan and decompose_goal plan history (one JSONL file per plan id)"
    ).default("${System.getProperty("user.home")}/.sophi/plans")
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
    ).flag("--no-memory", default = false)
    private val embeddingModel: String? by option(
        "--embedding-model",
        help = "Embedding model name for --memory, e.g. nomic-embed-text (Ollama) or text-embedding-3-small"
    )
    private val embeddingBaseUrl: String? by option(
        "--embedding-base-url",
        help = "Embeddings endpoint base URL (defaults to --base-url; e.g. http://localhost:11434/v1)"
    )
    private val embeddingDimensions: Int? by option(
        "--embedding-dimensions",
        help = "Embedding vector dimensions (768 for nomic-embed-text, 1536 for text-embedding-3-small; " +
            "default 1536, or 768 when the local server's nomic-embed model is picked for you)"
    ).int()
    private val tokenViewKey: String by option(
        "--token-view-key",
        help = "Keyboard shortcut to toggle token visibility during streaming (default: Ctrl+T; a printable key can no longer be typed into a steer)"
    ).default(DEFAULT_TOKEN_VIEW_KEY.toString())
    private val autoExitTokenView: Boolean by option(
        "--auto-exit-token-view",
        help = "Automatically exit token view when LLM finishes (default: true)"
    ).flag("--no-auto-exit-token-view", default = true)
    private val autoMode: Boolean by option(
        "--auto",
        help = "Start with auto mode enabled: low-risk tool calls (per rule + LLM classifier) run " +
            "without a confirmation prompt. Toggle anytime with /auto."
    ).flag("--no-auto", default = false)
    private val godMode: Boolean by option(
        "--god-mode",
        help = "Skip the LLM classifier entirely: trust only each tool's own rule checks. Prompts " +
            "only for calls a rule explicitly flags HIGH_RISK; everything else (including anything " +
            "a rule has no opinion on) runs unattended for the whole session. Fixed once set — no " +
            "/auto toggle while in this mode. Overrides --auto if both are passed."
    ).flag("--no-god-mode", default = false)
    private val goalEscalationModel: String? by option(
        "--goal-escalation-model",
        help = "Stronger model /goal escalates a plan step to when its confidence is below " +
            "threshold. Omit to disable escalation (a low-confidence step just fails and " +
            "replans instead)."
    )
    private val hubPort: Int by option(
        "--hub-port",
        help = "Port a running sophi-companion's hub listens on. This session registers with " +
            "it automatically if reachable; unreachable is silent, not an error."
    ).int().default(8765)
    private val sandboxMode: String by option(
        "--sandbox",
        help = "Where bash runs: off (default), seatbelt (macOS sandbox: writes only to the workspace, temp " +
            "and tool caches) or docker (a locked-down container sharing only the workspace). Change it " +
            "mid-session with /settings."
    ).choice("off", "seatbelt", "docker").default("off")
    private val network: Boolean by option(
        "--network",
        help = "Let sandboxed bash use the network (default on; --no-network blocks it). No effect with --sandbox off."
    ).flag("--no-network", default = true)
    private val dockerImage: String by option(
        "--docker-image",
        help = "Image for --sandbox docker (default $DEFAULT_DOCKER_IMAGE: Debian with git, curl and ssh)."
    ).default(DEFAULT_DOCKER_IMAGE)
    private val noRemote: Boolean by option(
        "--no-remote",
        help = "Do not register this session with a running companion's hub — stays fully " +
            "local, same as every sophi-cli release before this flag existed."
    ).flag("--remote", default = false)
    private val catalogRefresh: Boolean by option(
        "--catalog-refresh",
        help = "Refresh the model catalog (windows, prices, capabilities) from models.dev in the background, " +
            "at most once a day (default on; --no-catalog-refresh never goes online for it)."
    ).flag("--no-catalog-refresh", default = true)
    private val maxCostUsd: Double? by option(
        "--max-cost-usd",
        help = "Stop the session once its model requests have cost this many US dollars (cloud providers; 0 = no budget). " +
            "Change it mid-session with /settings max-cost-usd."
    ).double()

    override fun run() = runBlocking {
        if (currentContext.invokedSubcommand != null) return@runBlocking
        val catalog = loadModelCatalog()
        // Off the critical path: this run uses what was loaded above; a fresh file applies from the next start.
        if (catalogRefresh) kotlin.concurrent.thread(isDaemon = true, name = "sophi-catalog-refresh") {
            dev.sophi.sdk.ModelCatalog.refreshIfStale(DEFAULT_MODELS_CACHE)
        }
        val resolved = providerOptions.resolveAndWarn(catalog)
        if (resolved.info?.tools == false) System.err.println(
            "${resolved.model}: the model catalog says it has no tool calling, so Sophi's tools may not work with it"
        )
        val model = resolved.model
        val contextWindowTokens = resolved.contextWindowTokens
        // One metered provider for everything this session asks a model: chat, /goal, /plan, /compact, the
        // auto-mode classifier. So all of it counts toward --max-cost-usd and follows /model.
        val provider = dev.sophi.sdk.MeteredProvider.wrap(
            providerOptions.buildProvider(resolved), model, catalog, resolved.cloud, resolved.catalogProvider
        )
        val embedding = chooseEmbedding(memoryEnabled, embeddingModel, embeddingBaseUrl, embeddingDimensions, resolved)
        val mordantTerminal = Terminal()
        val sophiTerminal = SophiTerminal.create()
        val inputSource: InputSource =
            if (sophiTerminal.isInteractive) JLineInputSource(sophiTerminal) else LegacyReadLineInputSource()

        val cli = exitOnBadPermissionRules { buildCliRuntime(
            opts = CliOptions(
                model = model,
                maxTokens = maxTokens,
                contextWindowTokens = contextWindowTokens,
                systemPrompt = systemPrompt,
                sessionsDir = sessionsDirStr,
                agentsDir = agentsDirStr,
                scheduleDir = scheduleDirStr,
                plansDir = plansDirStr,
                mcpConfigPath = mcpConfigPathStr,
                braveApiKey = braveApiKeyOption,
                autoMode = autoMode,
                godMode = godMode,
                memoryEnabled = memoryEnabled,
                embeddingModel = embedding.model,
                embeddingBaseUrl = embedding.baseUrl,
                embeddingDimensions = embedding.dimensions,
                baseUrl = resolved.baseUrl,
                apiKey = providerOptions.apiKey,
                llmTimeoutSeconds = providerOptions.timeoutSeconds,
                hubPort = hubPort,
                noRemote = noRemote,
                sessionIdToResume = sessionId,
                maxCostUsd = maxCostUsd,
            ),
            provider = provider,
            terminal = mordantTerminal,
            input = inputSource,
            // Encoding runs fire-and-forget on AFTER_TURN (MemoryPlugin), so this warning can
            // arrive at any time relative to the next readLine() prompt — printAbove keeps it
            // from landing glued onto that prompt's line.
            onWarning = { msg ->
                if (sophiTerminal.isInteractive) sophiTerminal.printAbove(TextColors.yellow(msg).toString())
                else mordantTerminal.println(TextColors.yellow(msg))
            }
        ) }
        val session = cli.session
        val hubClient = cli.hubClient
        val learningPlugin = cli.runtime.learningPlugin
        val memoryPlugin = cli.runtime.memoryPlugin

        // Retries connect() on a timer rather than once at startup: a companion opened after
        // this CLI session already started must still be able to pick it up (and a companion
        // that restarts mid-session must be reconnected to), not just one whose hub was already
        // listening at the moment this process launched.
        if (hubClient != null) {
            launch {
                maintainHubConnection(hubClient, this) {
                    hubClient.publish(
                        HubEvent.SessionRegistered(
                            sessionId = session.id,
                            title = session.title,
                            pid = ProcessHandle.current().pid(),
                            cwd = System.getProperty("user.dir")
                        )
                    )
                }
            }
        }

        val compactor = ContextCompactor(provider, dev.sophi.core.context.Summariser(provider, cli.runtime.compactionMode, contextWindowTokens, dev.sophi.ai.system1.Judgments.COMPACTOR))

        mordantTerminal.println(TextColors.cyan("Sophi — session ${session.id}"))
        mordantTerminal.println(
            "Type 'exit' or 'quit' to end. Commands: /list /branch /checkout /undo /revert /compact /good /bad " +
                "/schedule /calendar /feedback /lessons /memory /skill /plan /goal /auto /permissions /settings /model\n"
        )
        // containers a killed sophi left behind; off the start path, since asking Docker can take seconds
        kotlin.concurrent.thread(isDaemon = true, name = "sophi-docker-orphans") {
            if (dev.sophi.core.tools.DockerSandbox.available) dev.sophi.core.tools.DockerSandbox.removeOrphans()
        }
        cli.runtime.bashSandbox?.let { sb ->
            val mode = SandboxMode.valueOf(sandboxMode.uppercase())
            if (mode == SandboxMode.DOCKER) mordantTerminal.println("Starting a container (the first run pulls the image)…")
            sb.set(mode, network, dockerImage)?.let { reason ->
                sb.set(SandboxMode.OFF, network, dockerImage) // kept, so a later switch uses them
                mordantTerminal.println(TextColors.yellow("sandbox: $reason — bash runs unsandboxed"))
            }
            if (sb.mode != SandboxMode.OFF) mordantTerminal.println(TextColors.cyan(sandboxStatus(sb)))
        }
        if (godMode) {
            if (autoMode) {
                mordantTerminal.println(
                    TextColors.yellow("--auto ignored: --god-mode is more permissive and already active")
                )
            }
            mordantTerminal.println(
                TextColors.red(
                    "God mode is on — only rule-flagged HIGH_RISK calls will prompt. " +
                        "Everything else runs unattended. Deny rules in permissions.json still apply."
                )
            )
        } else if (autoMode) {
            mordantTerminal.println(
                TextColors.cyan("Auto mode is on — low-risk tool calls run without asking. Toggle with /auto.")
            )
        }
        cli.runtime.permissionRules?.untrusted?.size?.takeIf { it > 0 }?.let { n ->
            mordantTerminal.println(TextColors.yellow(
                "$n allow rule(s) in .sophi/permissions.json are not trusted yet — /permissions to review, /permissions trust to use them."
            ))
        }

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
        // The plugin-hook bridge now lives inside SophiRuntime.streamTurn, so this only has to
        // mirror events out to a connected companion.
        val onEvent: suspend (dev.sophi.core.agent.TurnEvent) -> Unit = { event ->
            event.toHubEvent(session.id)?.let { hubClient?.publish(it) }
        }
        // /goal drives PlanRunner directly rather than going through runtime.streamTurn, so it
        // needs its own AgentLoop over the same registry and confirmation policy — the same
        // arrangement buildPlanRunner makes for /plan. It is NOT built via buildPlanRunner
        // because the preview flow has to generate the plan with the very planner the runner
        // will later replan with, and buildPlanRunner owns its planner privately.
        val goalPlanner = dev.sophi.core.agent.plan.LlmPlanner(
            provider, model,
            contextProvider = { goalPrompt -> cli.runtime.contextFor(session.id, goalPrompt) }
        )
        val goalLoop = AgentLoop(
            provider, cli.registry, cli.runtime.sessionManager,
            confirmationPolicy = cli.confirmationPolicy, contextWindowTokens = contextWindowTokens,
            audit = cli.runtime.auditSink, permissions = cli.runtime.permissionRules
        )
        // /goal's planner, critic and runner keep the start model's id; the metered provider sends it to the active model.
        val switchModel: suspend (String) -> String? = { id ->
            val server = resolved.server
            if (server != null && id !in server.models) "$id isn't on the server at ${server.baseUrl}"
            else {
                // Local: the server's window for that model; cloud: the catalog's; else keep the current one.
                val window = (if (server != null) dev.sophi.ai.providers.detectContextWindow(server, id)?.tokens
                    else catalog.find(id, resolved.catalogProvider)?.contextTokens) ?: cli.runtime.contextWindowTokens
                cli.runtime.switchModel(id, window)
                goalLoop.contextWindowTokens = window
                null
            }
        }
        val goalController = dev.sophi.cli.goal.GoalController(
            agentLoop = goalLoop,
            sessionManager = cli.runtime.sessionManager, provider = provider,
            planner = goalPlanner,
            critic = dev.sophi.core.agent.plan.LlmStepCritic(provider, model),
            runnerConfig = dev.sophi.core.agent.plan.PlanRunnerConfig(
                model = model, maxTokens = maxTokens, systemPrompt = cli.runtime.config.systemPrompt,
                escalationModel = goalEscalationModel, allowParallelSteps = false
            ),
            planLog = cli.planLog,
            input = inputSource, liveRegion = liveRegion, interactive = sophiTerminal.isInteractive,
            tokenViewKey = tokenViewKey.singleOrNull() ?: DEFAULT_TOKEN_VIEW_KEY, autoExitTokenView = autoExitTokenView,
            learning = learningPlugin,
            checkpoints = cli.runtime.checkpointStore,
            onEvent = onEvent,
            onTurnSettled = { userInput, assistantReply, error ->
                cli.runtime.settleExternalTurn(session.id, userInput, assistantReply, error)
            }
        ) { mordantTerminal.println(it) }
        val slashHandler = SlashHandler(
            cli.runtime.sessionManager, compactor, cli.runtime.config, learningPlugin,
            scheduleDir = Path.of(scheduleDirStr), memoryPlugin = memoryPlugin,
            skillRegistry = cli.skillRegistry,
            provider = provider, calendarProvider = cli.calendarProvider, confirmationPolicy = cli.confirmationPolicy,
            autoModeToggle = cli.autoModeToggle,
            toolRegistry = cli.registry,
            planLog = cli.planLog,
            fileRestorer = cli.runtime.fileRestorer,
            auditSink = cli.runtime.auditSink,
            permissions = cli.runtime.permissionRules,
            checkpoints = cli.runtime.checkpointStore,
            settings = SettingsController(
                settings, currentContext.originalArgv, effectiveValues(),
                registeredOptions().associate { o -> o.names.maxBy { it.length }.removePrefix("--") to o.optionHelp(currentContext) },
                cli.runtime.bashSandbox, cli.autoModeToggle, interactive = sophiTerminal.isInteractive,
                costMeter = cli.runtime.costMeter, currentModel = { cli.runtime.config.model }, switchModel = switchModel,
            ),
            contextWindowTokens = contextWindowTokens,
            live = { cli.runtime.config to cli.runtime.contextWindowTokens },
            liveRegion = liveRegion,
            onEvent = onEvent,
            input = inputSource,
            goalController = goalController
        ) { mordantTerminal.println(it) }
        if (tokenViewKey.length != 1) {
            mordantTerminal.println(TextColors.yellow(
                "token view: --token-view-key must be a single character, got \"$tokenViewKey\" — using default Ctrl+T"))
        }
        val turnController = TurnController(
            cli.runtime, inputSource, liveRegion, onEvent = onEvent,
            tokenViewKey = tokenViewKey.singleOrNull() ?: DEFAULT_TOKEN_VIEW_KEY,
            autoExitTokenView = autoExitTokenView,
            usageLine = true, cloud = resolved.cloud,
        ) {
            mordantTerminal.println(it)
        }
        val hubMessages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        if (hubClient != null) {
            launch {
                hubClient.commands
                    .filterIsInstance<HubCommand.SendMessage>()
                    .collect { hubMessages.trySend(it.text) }
            }
        }
        val engine = TuiEngine(turnController, slashHandler, inputSource, hubMessages.takeIf { hubClient != null })

        try {
            engine.run(session)
        } finally {
            // TuiEngine.run returns on both exit paths (exit/quit and EOF); record the outcome once.
            // Goes through SophiRuntime.recordSessionEnd, not learningPlugin directly, so memory's
            // outcome-driven forgetting gets the same signal without this host wiring it itself.
            runCatching { cli.runtime.recordSessionEnd(session.id) }
            runCatching {
                hubClient?.publish(HubEvent.SessionClosed(session.id)) // no-op if never connected
                hubClient?.close()
            }
            runCatching {
                memoryPlugin?.let { mp ->
                    mp.consolidateIfDue()?.let { report ->
                        if (report.total > 0) mordantTerminal.println(
                            "memory: consolidated (merged=${report.merged} strengthened=${report.strengthened} " +
                            "compressed=${report.compressed} pruned=${report.pruned} purged=${report.purged} " +
                            "classified=${report.classified})")
                    }
                    mp.close()
                }
            }
            sophiTerminal.close()
            cli.runtime.close()
        }
        mordantTerminal.println(TextColors.cyan("\nSession ${session.id} ended."))
    }
}

private class LegacyReadLineInputSource : InputSource {
    override suspend fun readLine(prefill: String): String? = kotlin.io.readLine()
    override suspend fun awaitEsc() {
        kotlinx.coroutines.delay(Long.MAX_VALUE)
    }
    override suspend fun awaitControlKeys(toggleKey: Char, onToggle: suspend () -> Unit, onKey: (suspend (TurnKey) -> Unit)?) {
        kotlinx.coroutines.delay(Long.MAX_VALUE)
    }
    // No raw-mode reader loop is running in this (non-interactive) mode, so a plain, independent
    // stdin read is safe here — unlike JLineInputSource, there's nothing for it to race against.
    override suspend fun awaitYesNo(): Boolean =
        kotlin.io.readlnOrNull()?.trim()?.equals("y", ignoreCase = true) == true

    override suspend fun awaitConfirmation(offerAlways: Boolean): ConfirmAnswer {
        val line = kotlin.io.readlnOrNull()?.trim()
        return when {
            line.equals("y", ignoreCase = true) -> ConfirmAnswer.YES
            offerAlways && line.equals("a", ignoreCase = true) -> ConfirmAnswer.ALWAYS
            else -> ConfirmAnswer.NO
        }
    }
}
