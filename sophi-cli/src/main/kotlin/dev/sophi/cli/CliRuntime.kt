package dev.sophi.cli

import com.github.ajalt.mordant.terminal.Terminal
import dev.sophi.ai.api.LLMProvider
import dev.sophi.calendar.provider.CalendarProvider
import dev.sophi.calendar.tools.CreateCalendarEventTool
import dev.sophi.calendar.tools.DeleteCalendarEventTool
import dev.sophi.calendar.tools.GetCalendarEventTool
import dev.sophi.calendar.tools.ListCalendarEventsTool
import dev.sophi.calendar.tools.ListCalendarsTool
import dev.sophi.calendar.tools.UpdateCalendarEventTool
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentDefinitionLoader
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.LoopGuardPolicy
import dev.sophi.core.agent.SubagentTool
import dev.sophi.core.agent.plan.DecomposeGoalTool
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.session.AgentSession
import dev.sophi.sdk.Sophi
import dev.sophi.sdk.SophiRuntime
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.AutoModeConfirmationPolicy
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.LlmRiskClassifier
import dev.sophi.core.tools.RiskClassifier
import dev.sophi.core.tools.ToggleableConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.PluginRegistry
import dev.sophi.hub.HubClient
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import dev.sophi.memory.MemoryPlugin
import dev.sophi.skills.SkillRegistry
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

/**
 * Everything `sophi` needs from its command-line flags to build a runtime. A plain value object
 * rather than the Clikt command itself, so the wiring can be built and asserted on in a test
 * without parsing a command line or starting a terminal.
 */
data class CliOptions(
    val model: String,
    val maxTokens: Int,
    val contextWindowTokens: Int,
    val systemPrompt: String?,
    val sessionsDir: String,
    val agentsDir: String,
    val scheduleDir: String,
    val plansDir: String,
    val mcpConfigPath: String,
    /**
     * Where learning writes its outcome/lesson logs. Defaults to [LearningConfig]'s own default,
     * so behavior is unchanged; it is settable so a test can point it at a temp directory instead
     * of the developer's real ~/.sophi/learning.
     */
    val learningHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "learning"),
    val braveApiKey: String? = null,
    val autoMode: Boolean = false,
    val godMode: Boolean = false,
    val memoryEnabled: Boolean = false,
    val embeddingModel: String? = null,
    val embeddingBaseUrl: String? = null,
    val embeddingDimensions: Int = 1536,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val llmTimeoutSeconds: Long = 60,
    val llmMaxRetries: Int = 2,
    val hubPort: Int = 8765,
    val noRemote: Boolean = false,
    val sessionIdToResume: String? = null
)

/**
 * The assembled CLI runtime: the agent wiring plus the collaborators `run()` and [SlashHandler]
 * still need directly.
 */
class CliRuntime(
    val runtime: SophiRuntime,
    val registry: ToolRegistry,
    val confirmationPolicy: ConfirmationPolicy,
    val autoModeToggle: ToggleableConfirmationPolicy?,
    val skillRegistry: SkillRegistry,
    val memoryPlugin: MemoryPlugin?,
    val planLog: PlanLog,
    val calendarProvider: CalendarProvider,
    val session: AgentSession,
    val hubClient: HubClient?
) {
    /** Session store the runtime built from `sessionsDir` — the CLI never makes a second one. */
    val sessionManager: SessionManager get() = runtime.sessionManager
    val config: AgentConfig get() = runtime.config
    val learningPlugin: LearningPlugin? get() = runtime.learningPlugin
}

/**
 * Builds the whole agent runtime for an interactive `sophi` session.
 *
 * [onWarning] receives non-fatal setup messages (currently only memory's) so this function does
 * not need to know whether the caller has a raw-mode terminal to print above.
 */
internal suspend fun buildCliRuntime(
    opts: CliOptions,
    provider: LLMProvider,
    terminal: Terminal,
    input: InputSource,
    onWarning: (String) -> Unit = {}
): CliRuntime {
    // Assigned once the runtime exists (below). The confirmation policy reads them lazily, which
    // is what breaks the cycle: the policy must exist before the runtime that owns the
    // SessionManager, but the session — and the hub client keyed to it — cannot exist before that.
    var session: AgentSession? = null
    var hubClient: HubClient? = null

    val registry = ToolRegistry()
    val manualConfirmationPolicy: ConfirmationPolicy = RemoteAwareConfirmationPolicy(
        TerminalConfirmationPolicy(terminal, input),
        { hubClient },
        { requireNotNull(session) { "confirmation requested before the session was created" }.id }
    )
    val toggleableConfirmationPolicy: ToggleableConfirmationPolicy? = if (opts.godMode) null else {
        val autoModePolicy = AutoModeConfirmationPolicy(
            registry,
            LlmRiskClassifier(
                provider, opts.model, maxTokens = opts.maxTokens,
                timeout = opts.llmTimeoutSeconds.seconds
            ),
            manualConfirmationPolicy
        )
        ToggleableConfirmationPolicy(
            autoModePolicy, manualConfirmationPolicy, autoModeEnabled = opts.autoMode
        )
    }
    val confirmationPolicy: ConfirmationPolicy = toggleableConfirmationPolicy
        ?: AutoModeConfirmationPolicy(registry, RiskClassifier.ALWAYS_LOW_RISK, manualConfirmationPolicy)
    val loopGuardPolicy = TerminalLoopGuardPolicy(terminal, input)

    val agentsDir = Path.of(opts.agentsDir).also { it.createDirectories() }
    val agentDefinitions = AgentDefinitionLoader().load(agentsDir)

    val skillRegistry = SkillRegistry.load(
        globalDir = Path.of(System.getProperty("user.home"), ".sophi", "skills"),
        projectDir = Path.of(".sophi", "skills")
    )

    buildBuiltinTools(opts.braveApiKey).forEach { registry.register(it) }
    // The schedule tool is registered by the builder's schedule(dir) below, into this same registry.
    val calendarProvider = buildCalendarProvider()
    registry.register(CreateCalendarEventTool(calendarProvider))
    registry.register(ListCalendarEventsTool(calendarProvider))
    registry.register(GetCalendarEventTool(calendarProvider))
    registry.register(UpdateCalendarEventTool(calendarProvider))
    registry.register(DeleteCalendarEventTool(calendarProvider))
    registry.register(ListCalendarsTool(calendarProvider))

    if (skillRegistry.all().isNotEmpty()) {
        registry.register(SkillTool(skillRegistry))
    }
    registry.register(InstallSkillTool())
    registry.register(WriteSkillTool())

    val memoryPlugin = buildMemoryPlugin(opts, provider, onWarning)
    val mcpConfigPath = Path.of(opts.mcpConfigPath)

    val runtime = Sophi.runtime {
        this.provider = provider
        model = opts.model
        maxTokens = opts.maxTokens
        contextWindowTokens(opts.contextWindowTokens)
        sessionsDir = Path.of(opts.sessionsDir)
        // The learning section is appended by the builder; only the caller's own sections go here.
        systemPrompt = listOfNotNull(
            opts.systemPrompt,
            if (memoryPlugin != null) dev.sophi.memory.MemoryPromptSection.TEXT else null
        ).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        toolRegistry(registry)
        loopGuard(loopGuardPolicy)
        confirmationPolicy(confirmationPolicy)
        learning(LearningConfig(home = opts.learningHome, sessionModel = opts.model))
        schedule(Path.of(opts.scheduleDir))
        // mcpConfig throws on a missing file; sophi-web guards identically today.
        if (mcpConfigPath.exists()) mcpConfig(mcpConfigPath)
        memoryPlugin?.let { plugin(it) }
    }

    session = opts.sessionIdToResume?.let { runtime.sessionManager.load(it) }
        ?: runtime.sessionManager.create()
    val currentSession = session!!
    // Retries connect() on a timer rather than once at startup: a companion opened after
    // this CLI session already started must still be able to pick it up (and a companion
    // that restarts mid-session must be reconnected to), not just one whose hub was already
    // listening at the moment this process launched.
    hubClient = if (opts.noRemote) null else HubClient(opts.hubPort, currentSession.id)
    runCatching {
        runtime.sessionManager.saveConfigSnapshot(
            currentSession.id, opts.model, runtime.config.systemPrompt
        )
    }

    // Registered after build() because both need the session id and the runtime's final config.
    // AgentLoop reads registry.definitions() per round, so tools added now are live from the
    // next turn — the same mechanism SophiRuntime.connectMcpServer relies on.
    if (agentDefinitions.isNotEmpty()) {
        registry.register(
            SubagentTool(
                definitions = agentDefinitions,
                provider = provider,
                fullRegistry = registry,
                sessionManager = runtime.sessionManager,
                parentSessionId = currentSession.id,
                parentConfig = runtime.config,
                contextWindowTokens = opts.contextWindowTokens,
                confirmationPolicy = confirmationPolicy
            )
        )
    }
    val planLog = PlanLog(Path.of(opts.plansDir))
    registry.register(
        DecomposeGoalTool(
            provider = provider,
            fullRegistry = registry,
            sessionManager = runtime.sessionManager,
            parentSessionId = currentSession.id,
            parentConfig = runtime.config,
            contextWindowTokens = opts.contextWindowTokens,
            planLog = planLog,
            confirmationPolicy = confirmationPolicy
        )
    )

    return CliRuntime(
        runtime = runtime,
        registry = registry,
        confirmationPolicy = confirmationPolicy,
        autoModeToggle = toggleableConfirmationPolicy,
        skillRegistry = skillRegistry,
        memoryPlugin = memoryPlugin,
        planLog = planLog,
        calendarProvider = calendarProvider,
        session = currentSession,
        hubClient = hubClient
    )
}

/**
 * Memory (Jane's Theory): per-turn recall via ContextContributor, async encoding on AFTER_TURN.
 * Returns null — with a warning — whenever memory was asked for but cannot be honored, rather
 * than failing the session or silently pretending it is on.
 */
private suspend fun buildMemoryPlugin(
    opts: CliOptions,
    provider: LLMProvider,
    onWarning: (String) -> Unit
): MemoryPlugin? {
    if (!opts.memoryEnabled) return null
    val embBase = opts.embeddingBaseUrl ?: opts.baseUrl
    val embModel = opts.embeddingModel
    if (embBase == null || embModel == null) {
        onWarning("memory: disabled — --memory needs --embedding-model and --embedding-base-url (or --base-url)")
        return null
    }
    val embProvider = dev.sophi.ai.providers.buildOpenAiCompatEmbeddingProvider(
        embBase, opts.apiKey, embModel, opts.embeddingDimensions
    )
    // Spec §6: memory must never fail silently (cognitive-prosthetic honesty).
    val probeResult = dev.sophi.ai.api.probeEmbeddingProvider(embProvider)
    if (probeResult.isFailure) {
        val error = probeResult.exceptionOrNull()?.message ?: "unknown error"
        onWarning("memory: disabled — embeddings endpoint unreachable at $embBase ($embModel): $error")
        return null
    }
    val palace = dev.sophi.memory.jane.JanesPalace(
        dev.sophi.memory.jane.JanesPalaceConfig(sessionModel = opts.model),
        provider, embProvider, embModel,
        onWarning = onWarning
    )
    return MemoryPlugin(palace)
}
