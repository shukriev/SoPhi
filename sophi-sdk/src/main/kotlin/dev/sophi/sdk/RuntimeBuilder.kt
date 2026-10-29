package dev.sophi.sdk

import dev.sophi.core.context.CompactionMode
import dev.sophi.core.context.ContextCompactor
import dev.sophi.core.context.Summariser
import dev.sophi.context.ContextBudget
import dev.sophi.context.ContextEngine
import dev.sophi.context.HintGate
import dev.sophi.context.HintPlugin
import dev.sophi.context.Orientation
import dev.sophi.context.ProjectOf
import dev.sophi.context.normalizeKnob
import dev.sophi.context.OrientationPlugin
import dev.sophi.context.ContextPlugin
import dev.sophi.context.tools.contextTools
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
import dev.sophi.core.agent.plan.PlanProgressEvent
import dev.sophi.core.audit.AuditSink
import dev.sophi.core.audit.JsonlAuditSink
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.undo.CheckpointStore
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.SophiPlugin
import dev.sophi.learning.LearningConfig
import dev.sophi.learning.LearningPlugin
import dev.sophi.learning.system1FromHome
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.config.DEFAULT_DEFER_TOOLS_OVER
import dev.sophi.mcp.config.McpConfigLoader
import dev.sophi.memory.MemoryPlugin
import dev.sophi.memory.MemoryPromptSection
import dev.sophi.memory.jane.JanesPalace
import dev.sophi.memory.jane.JanesPalaceConfig
import dev.sophi.memory.tools.ListActionablePatternsTool
import dev.sophi.memory.tools.ListHabitsTool
import dev.sophi.memory.tools.ListOpenCommitmentsTool
import dev.sophi.schedule.store.TaskStore
import dev.sophi.schedule.tools.ScheduleTaskTool
import dev.sophi.skills.SkillInvocationStore
import dev.sophi.skills.SkillRegistry
import dev.sophi.versioning.VersionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Path

class RuntimeBuilder {
    var provider: LLMProvider? = null
    /** Opt-in JSONL usage log (numbers only, never content): input = all prompt tokens, cacheRead = the part served from cache. Internal experiment knob. */
    var usageLogPath: Path? = System.getenv("SOPHI_USAGE_LOG")?.let(Path::of)
    /** Internal experiment knobs (SOPHI_CONTEXT_HINT, SOPHI_CONTEXT_ORIENTATION, SOPHI_CONTEXT_TOOLS); tests override. */
    var contextKnobs: Map<String, String> = System.getenv()
    /** Case-insensitive knob lookup; an unset or unknown value falls back to [default]. */
    private fun knob(name: String, default: String, vararg others: String): String =
        normalizeKnob(contextKnobs[name], default, *others)
    /** Global instructions file for the orientation section; null = none. */
    var contextGlobalInstructions: Path? = Orientation.defaultGlobal()
    /** Required before [build]: SoPhi never picks a model for you. */
    var model: String? = null
    /** Prices, windows and capabilities; with [priced] true, each request's cost is counted from it. */
    var modelCatalog: ModelCatalog? = null
    /** Whether this provider bills (a cloud API); false for a local server, whose requests cost nothing. */
    var priced: Boolean = false
    /** The catalog provider to prefer when an id is listed by several (`anthropic`, `openrouter`, …). */
    var catalogProvider: String? = null
    private var maxUsd: Double? = null
    /**
     * A meter the host already put under its own wrappers (e.g. System 1, so locally served judgments
     * aren't billed): [provider] is then used as is and this one counts. Null = the builder wraps [provider].
     */
    var metered: MeteredProvider? = null
    /** Stops the session's work once it has cost [maxUsd] dollars: the turn ends with TurnStopReason.BudgetExceeded. */
    fun budget(maxUsd: Double): RuntimeBuilder = apply { this.maxUsd = maxUsd }
    var maxTokens: Int = 4096
    var temperature: Double = 0.7
    var systemPrompt: String? = null
    var sessionsDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "sessions")
    var skillsDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "skills")
    var memoryHome: Path = JanesPalaceConfig.DEFAULT_HOME
    /** The shared server root memory lives on; null = shared only when [memoryHome] is the default (ADR-041 M6),
     *  embedded at any other home. Tests set both to temp directories. */
    var memorySharedRoot: Path? = null

    private val tools: MutableList<Tool> = mutableListOf()
    private val plugins: MutableList<SophiPlugin> = mutableListOf()
    private var confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.DENY_ALL
    private var grants: Set<String> = emptySet()
    private var mcpConfigPath: Path? = null
    private var deferToolsOver: Int = DEFAULT_DEFER_TOOLS_OVER
    private var system1Config: Pair<Path, (String) -> Unit>? = null
    private var mcpClientManager: McpClientManager = McpClientManager()
    private var providedRegistry: ToolRegistry? = null
    private var loopGuardPolicy: LoopGuardPolicy = LoopGuardPolicy.NEVER_CONTINUE
    private var learningConfig: LearningConfig? = null
    private var autoTitles: ((String, String) -> Unit)? = null

    /** Names untitled sessions after their first turn with one short model call (see SessionTitlePlugin).
     *  [onTitled] is told when a title is set, e.g. to refresh a session list. */
    fun autoTitleSessions(onTitled: (sessionId: String, title: String) -> Unit = { _, _ -> }): RuntimeBuilder =
        apply { autoTitles = onTitled }
    private var learningEmbeddingProvider: EmbeddingProvider? = null
    private var scheduleDir: Path? = null
    private var contextWindowTokens: Int? = null
    private var memoryConfig: MemoryConfig? = null
    private var agentsDirConfig: AgentsDirConfig? = null
    private var builtinToolsConfig: BuiltinToolsConfig? = null
    private var checkpointsConfig: Pair<Path, (String) -> Unit>? = null
    private var auditSink: AuditSink? = null
    private var permissionRules: dev.sophi.core.permissions.PermissionRules? = null
    private var contextConfig: Triple<ContextEngine, ProjectOf, Boolean>? = null
    private var subagentDelegationEnabled: Boolean = false
    private var goalDecompositionPlansDir: Path? = null
    private var goalDecompositionOnProgress: suspend (PlanProgressEvent) -> Unit = {}
    private var skillToolsEnabled: Boolean = false
    private var includeAutoSkills: () -> Boolean = { true }
    private var configVersionRef: Pair<String, VersionStore>? = null

    fun tool(t: Tool): RuntimeBuilder = apply { tools.add(t) }
    /**
     * Gives the agent the context index (ADR-041): search_context, read_context, project_map, find_symbol, related (SOPHI_CONTEXT_TOOLS=all|core|none
     * picks all five, search + read only, or none), and
     * a rescan after it edits files. [project] returns the session's bound project id (null = unbound: only global projects are searched, no orientation).
     * With [owned] the runtime closes [engine] on [SophiRuntime.close].
     */
    fun context(engine: ContextEngine, owned: Boolean = true, project: ProjectOf): RuntimeBuilder =
        apply { contextConfig = Triple(engine, project, owned) }

    fun plugin(p: SophiPlugin): RuntimeBuilder = apply { plugins.add(p) }
    fun confirmationPolicy(policy: ConfirmationPolicy): RuntimeBuilder = apply { confirmationPolicy = policy }
    fun grants(names: Set<String>): RuntimeBuilder = apply { grants = names }
    fun mcpConfig(path: Path): RuntimeBuilder = apply { mcpConfigPath = path }
    /** Deferral threshold for MCP servers when no mcp.json is given (a loaded mcp.json's own value wins). */
    fun deferToolsOver(n: Int): RuntimeBuilder = apply { deferToolsOver = n }
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

    /** Wraps the provider with the System-1 tier from `[home]/system1.json` (ADR-042); no file, no change. */
    fun system1(
        home: Path = Path.of(System.getProperty("user.home"), ".sophi"),
        onWarning: (String) -> Unit = { System.err.println(it) }
    ): RuntimeBuilder = apply { system1Config = home to onWarning }

    /**
     * Loads AgentDefinitions from [dir] (created if missing) for this runtime's scheduleEngine()
     * calls to enforce as an allowlist. [onWarning] fires — and definitions stay empty — if any
     * file in [dir] fails to parse; never throws (see AgentDefinitionLoader.loadOrWarn).
     */
    fun agentsDir(dir: Path, onWarning: (String) -> Unit = { System.err.println(it) }): RuntimeBuilder =
        apply { agentsDirConfig = AgentsDirConfig(dir, onWarning) }

    /** Registers the standard file/shell/search/date tool set — see [buildBuiltinTools]. */
    fun builtinTools(root: Path, braveApiKey: String? = null, sandboxed: Boolean = false): RuntimeBuilder =
        apply { builtinToolsConfig = BuiltinToolsConfig(root, braveApiKey, sandboxed) }

    /**
     * Shadow-git snapshots of the [builtinTools] root before each round that changes files, so
     * [SophiRuntime.fileRestorer] can undo them. Needs [builtinTools]; [build] fails otherwise.
     * Snapshots live under [home], outside the workspace. [onWarning] fires when one can't be taken.
     */
    fun checkpoints(
        enabled: Boolean = true,
        home: Path = Path.of(System.getProperty("user.home"), ".sophi", "checkpoints"),
        onWarning: (String) -> Unit = { System.err.println(it) }
    ): RuntimeBuilder = apply { checkpointsConfig = if (enabled) home to onWarning else null }

    /**
     * Appends every tool call, approved or denied, to a JSONL file with actor, rule and result
     * (secrets redacted). Scheduled tasks audit to the same sink. [onWarning] fires when a write fails.
     */
    fun auditLog(
        path: Path = Path.of(System.getProperty("user.home"), ".sophi", "audit.jsonl"),
        onWarning: (String) -> Unit = { System.err.println(it) }
    ): RuntimeBuilder = audit(JsonlAuditSink(path, onWarning))

    /** Sends audit entries to a custom [sink] (retention, export). The last of [auditLog] and this wins. */
    fun audit(sink: AuditSink): RuntimeBuilder = apply { auditSink = sink }

    /**
     * Loads permission rules from [home] (global) and [projectRoot]/.sophi (project); every loop the runtime builds
     * enforces them. Loads now, so a malformed file throws PermissionRulesException before [build] is reached.
     * The last of this and [permissions] wins.
     */
    fun permissionRules(
        projectRoot: Path,
        home: Path = Path.of(System.getProperty("user.home"), ".sophi")
    ): RuntimeBuilder = permissions(loadPermissionRules(projectRoot, home))

    /** Enforces already-loaded [rules]. The last of [permissionRules] and this wins. */
    fun permissions(rules: dev.sophi.core.permissions.PermissionRules): RuntimeBuilder = apply { permissionRules = rules }

    /**
     * Registers a `delegate_to_subagent` tool built from whatever [agentsDir] loaded — a no-op
     * if [agentsDir] was never called or found no definitions, matching [SubagentTool]'s own
     * "definitions is empty" guard. Safe to call without [agentsDir]; just inert.
     */
    fun subagentDelegation(): RuntimeBuilder = apply { subagentDelegationEnabled = true }

    /** Registers a `decompose_goal` tool; every plan version is logged under [plansDir] ([PlanLog] creates it if missing). */
    fun goalDecomposition(
        plansDir: Path,
        onProgress: suspend (PlanProgressEvent) -> Unit = {}
    ): RuntimeBuilder = apply {
        goalDecompositionPlansDir = plansDir
        goalDecompositionOnProgress = onProgress
    }

    /**
     * Registers `skill`, `install_skill` and `write_skill`. `skill` always registers and reloads
     * [skillsDir] plus this project's `.sophi/skills` on every use, so skills learned mid-session
     * (auto-learned ones) are usable at once. [includeAutoSkills] false hides `auto-*` skills.
     */
    fun skillTools(includeAutoSkills: () -> Boolean = { true }): RuntimeBuilder = apply {
        skillToolsEnabled = true
        this.includeAutoSkills = includeAutoSkills
    }

    /**
     * Loads the [HarnessConfig] recorded as [id] in [versionStore] and applies whichever of its
     * fields this builder can reach directly: [systemPrompt], [temperature], [maxTokens],
     * and — when [learning] is also active — [LearningConfig.maxRecalledLessons]. The remaining
     * `HarnessConfig` fields (`criticEnabled`, `topKSkills`, `toolDescriptionOverrides`) are read
     * by other integration points, not applied here — see [HarnessConfig]'s own doc comment.
     * An unknown [id] is silently ignored, leaving this builder's own fields in effect; omitting
     * this call entirely preserves today's exact default behavior.
     */
    fun configVersion(id: String, versionStore: VersionStore): RuntimeBuilder = apply { configVersionRef = id to versionStore }

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
        val model = requireNotNull(model) { "model must be set before calling build()" }
        // Innermost of the wrappers, so a judgment System 1 serves locally is never billed or refused.
        val given = requireNotNull(provider) { "provider must be set before calling build()" }
        val hostMeter = metered
        val metered = hostMeter ?: MeteredProvider.wrap(given, model, modelCatalog, priced, catalogProvider)
        maxUsd?.let { metered.meter.maxUsd = it }
        val inner: LLMProvider = if (hostMeter != null) given else metered
        val p = inner.let { s2 -> system1Config?.let { (home, warn) -> system1FromHome(s2, home, warn) } ?: s2 }
        val window = requireNotNull(contextWindowTokens) {
            "contextWindowTokens must be set before calling build() — pass the total context " +
                "window (in tokens) of the model you configured"
        }
        val agentDefinitions = agentsDirConfig?.let { cfg ->
            AgentDefinitionLoader().loadOrWarn(cfg.dir, cfg.onWarning)
        } ?: emptyList()
        val registry = (providedRegistry ?: ToolRegistry()).also { r -> tools.forEach { r.register(it) } }
        val harnessConfig: HarnessConfig? = configVersionRef?.let { (id, store) ->
            store.get(id)?.let { v -> Json.decodeFromString<HarnessConfig>(v.content) }
        }
        val bashSandbox = builtinToolsConfig?.let { cfg ->
            dev.sophi.core.tools.BashSandbox(cfg.root).apply { if (cfg.sandboxed) set(dev.sophi.core.tools.SandboxMode.SEATBELT) }
                .also { s -> buildBuiltinTools(cfg.root, cfg.braveApiKey, sandbox = s).forEach { registry.register(it) } }
        }
        contextConfig?.let { (engine, project, _) ->
            contextTools(engine, project, knob("SOPHI_CONTEXT_TOOLS", "all", "core", "none")).forEach { registry.register(it) }
            plugins.add(ContextPlugin(engine, project))
            val budget = ContextBudget(window)
            plugins.add(OrientationPlugin(engine, project, budget, knob("SOPHI_CONTEXT_ORIENTATION", "both", "instructions", "map", "off"),
                globalInstructions = contextGlobalInstructions))
            plugins.add(HintPlugin(engine, project, budget, HintGate(), knob("SOPHI_CONTEXT_HINT", "gated", "always", "off")))
        }
        if (skillToolsEnabled) {
            val loadSkills = skillRegistryLoader(skillsDir, Path.of(".sophi", "skills"), includeAutoSkills)
            registry.register(SkillTool(loadSkills, topK = harnessConfig?.topKSkills))
            registry.register(InstallSkillTool())
            registry.register(WriteSkillTool())
            // Recall is wired with the skill tools because it is useless without them: the pointer
            // it injects tells the model to call skill(name=...).
            plugins.add(SiteSkillRecallPlugin(loadSkills))
        }
        scheduleDir?.let { dir ->
            registry.register(ScheduleTaskTool(
                TaskStore(dir.resolve("tasks.json")),
                dev.sophi.schedule.store.RunLog(dir.resolve("runs.jsonl"))
            ))
        }
        val mcp = mcpConfigPath?.let { McpConfigLoader().load(it) }
        val deferOver = mcp?.deferToolsOver ?: deferToolsOver
        val mcpServers = mcp?.servers?.filter { server -> server.enabled } ?: emptyList()
        runBlocking { mcpClientManager.connect(mcpServers, deferOver) }.forEach { registry.register(it) }
        registry.registerToolSearchIfDeferred()
        val sm = FileSessionManager(sessionsDir)
        val agentConfig = AgentConfig(
            model = model,
            maxTokens = harnessConfig?.maxTokens ?: maxTokens,
            temperature = harnessConfig?.temperature ?: temperature,
            systemPrompt = harnessConfig?.systemPrompt ?: systemPrompt
        )
        val effectiveLearningConfig = harnessConfig?.let { hc -> learningConfig?.copy(maxRecalledLessons = hc.maxRecalledLessons) } ?: learningConfig
        val compactionMode = when (knob("SOPHI_COMPACTION", "structured", "elide", "generic")) {
            "elide" -> CompactionMode.ELIDE
            "generic" -> CompactionMode.GENERIC
            else -> CompactionMode.STRUCTURED
        }
        val checkpointStore = checkpointsConfig?.let { (home, warn) ->
            val cfg = checkNotNull(builtinToolsConfig) { "checkpoints() needs builtinTools(root)" }
            CheckpointStore(cfg.root, home, onWarning = warn)
        }
        val loop = AgentLoop(
            p, registry, sm,
            compactor = ContextCompactor(p, Summariser(p, compactionMode, window, dev.sophi.ai.system1.Judgments.COMPACTOR)), compaction = compactionMode,
            confirmationPolicy = confirmationPolicy, grants = grants,
            loopGuard = loopGuardPolicy,
            contextWindowTokens = window,
            onUsage = usageLogPath?.let { path ->
                { u: dev.sophi.ai.api.TokenUsage, kind: String ->
                    val line = """{"ts":${System.currentTimeMillis()},"model":${Json.encodeToString(metered.activeModel)},"kind":"$kind","input":${u.inputTokens},"output":${u.outputTokens},"cacheRead":${u.cacheReadTokens}}"""
                    path.parent?.let { java.nio.file.Files.createDirectories(it) }
                    java.nio.file.Files.writeString(path, line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
                }
            } ?: { _, _ -> },
            checkpoints = checkpointStore,
            audit = auditSink, permissions = permissionRules
        )
        val pluginRegistry = PluginRegistry().also { r -> plugins.forEach { r.register(it) } }
        SkillInvocationPlugin(SkillInvocationStore(skillsDir.resolve(".invocations.jsonl")))
            .also { pluginRegistry.register(it) }
        autoTitles?.let { onTitled -> pluginRegistry.register(SessionTitlePlugin(p, model, sm, onTitled)) }

        val learningPlugin = effectiveLearningConfig?.let { cfg ->
            LearningPlugin(cfg.copy(sessionModel = agentConfig.model), model = agentConfig.model, provider = p,
                sessionManager = sm, embeddingProvider = learningEmbeddingProvider).also { plugin ->
                pluginRegistry.register(plugin)
            }
        }
        val learningSection = learningPlugin?.let { it.promptSections(effectiveLearningConfig!!.scope) }

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
                // A memory database that can't open (an older Sophi holding unmigrated data, a schema
                // mismatch, corruption) degrades like a failed embeddings probe instead of crashing build().
                runCatching {
                    JanesPalace(
                        JanesPalaceConfig(
                            home = memoryHome, sessionModel = model,
                            autoPurgeEnabled = JanesPalaceConfig.autoPurgeEnabledFromEnv(),
                            encoderTelemetry = JanesPalaceConfig.encoderTelemetryFromEnv(),
                            sharedRoot = memorySharedRoot ?: JanesPalaceConfig.sharedRootFor(memoryHome)
                        ),
                        p, embeddingProvider, mc.embeddingModel, onWarning = mc.onWarning
                    )
                }.fold(
                    onSuccess = { palace -> MemoryPlugin(palace).also { pluginRegistry.register(it) } },
                    onFailure = { e ->
                        mc.onWarning("memory: disabled — couldn't open the memory database at $memoryHome (${e.message ?: "unknown error"})")
                        null
                    }
                )
            }
        }
        memoryPlugin?.palace()?.let { palace -> registry.register(ListActionablePatternsTool(palace)) }
        memoryPlugin?.palace()?.let { palace -> registry.register(ListOpenCommitmentsTool(palace)) }
        memoryPlugin?.palace()?.let { palace -> registry.register(ListHabitsTool(palace)) }
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
                    confirmationPolicy = confirmationPolicy,
                    currentWindow = { loop.contextWindowTokens },
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
                    confirmationPolicy = confirmationPolicy,
                    onProgress = goalDecompositionOnProgress,
                    currentWindow = { loop.contextWindowTokens },
                )
            )
        }

        // Applied last, after every other tool registration above, so an override reaches
        // whichever tool ends up registered under that name regardless of registration order.
        harnessConfig?.toolDescriptionOverrides?.forEach { (toolName, overrideDescription) ->
            registry.getOrNull(toolName)?.let { original ->
                registry.register(DescriptionOverrideTool(original, overrideDescription))
            }
        }

        val schedulesDir = scheduleDir ?: Path.of(System.getProperty("user.home"), ".sophi", "schedule")
        return SophiRuntime(
            loop, sm, pluginRegistry, effectiveConfig, mcpClientManager, learningPlugin, registry, p, window,
            skillsDir, memoryPlugin, agentDefinitions, schedulesDir, contextConfig?.takeIf { it.third }?.first, compactionMode,
            deferOver, checkpointStore, auditSink, permissionRules, bashSandbox, metered
        )
    }
}

/** Substitutes [description] for [delegate]'s own, without changing the [Tool] SPI itself. */
private class DescriptionOverrideTool(private val delegate: Tool, override val description: String) : Tool by delegate

private data class AgentsDirConfig(val dir: Path, val onWarning: (String) -> Unit)

private data class BuiltinToolsConfig(val root: Path, val braveApiKey: String?, val sandboxed: Boolean)

private data class MemoryConfig(
    val embeddingModel: String,
    val embeddingBaseUrl: String,
    val embeddingApiKey: String?,
    val embeddingDimensions: Int,
    val onWarning: (String) -> Unit,
    val embeddingProvider: EmbeddingProvider? = null
)

/**
 * Called on every use of the skill tool; re-parses only when a skill file was added, removed or
 * changed (or [includeAutoSkills] flipped), and drops `auto-*` skills while it says so.
 * A skill learned in another chat still changes the tool list mid-turn: one prompt-cache miss on
 * a local server, once per learned skill.
 */
internal fun skillRegistryLoader(globalDir: Path, projectDir: Path, includeAutoSkills: () -> Boolean): () -> SkillRegistry {
    val lock = Any()
    var key: Any? = null
    var cached: SkillRegistry? = null
    return {
        val include = includeAutoSkills()
        val now = Triple(skillFiles(globalDir), skillFiles(projectDir), include)
        synchronized(lock) {
            cached?.takeIf { key == now } ?: SkillRegistry.load(globalDir, projectDir)
                .let { r -> if (include) r else r.filter { !isAutoSkillId(it) } }
                .also { cached = it; key = now }
        }
    }
}

/** Path, size and modification time of every skill file, two levels deep (flat skills and domain
 *  members). A listing that fails mid-change gets a fresh key, so the next load re-parses. */
private fun skillFiles(dir: Path): Any = runCatching {
    if (!java.nio.file.Files.isDirectory(dir)) emptyList()
    else java.nio.file.Files.walk(dir, 2).use { paths ->
        paths.filter { it.toString().endsWith(".md") && java.nio.file.Files.isRegularFile(it) }.toList()
    }.map { Triple(it.toString(), java.nio.file.Files.size(it), java.nio.file.Files.getLastModifiedTime(it).toInstant()) }
        .sortedBy { it.first }
}.getOrElse { Any() }
