package dev.sophi.cli

import dev.sophi.memory.MemoryPlugin
import dev.sophi.schedule.engine.ScheduleEngine
import dev.sophi.sdk.Sophi
import dev.sophi.sdk.TournamentTool
import dev.sophi.sdk.buildBuiltinTools
import java.nio.file.Path

internal data class ScheduleRuntime(val engine: ScheduleEngine, val memoryPlugin: MemoryPlugin?)

internal fun buildScheduleEngine(
    model: String,
    providerType: String,
    apiKeyOption: String?,
    baseUrl: String?,
    scheduleDir: Path,
    sessionsDir: Path,
    agentsDir: Path,
    braveApiKeyOption: String?,
    contextWindowTokens: Int,
    taskTimeoutSeconds: Long = 300,
    maxTokens: Int = 4096,
    memoryHome: Path? = null,
    embeddingModel: String? = null,
    embeddingBaseUrl: String? = null,
    embeddingApiKey: String? = null,
    embeddingDimensions: Int = 1536,
    onWarning: (String) -> Unit = {},
    versioningHome: Path = defaultVersioningHome,
    evalsDir: Path = Path.of("evals")
): ScheduleRuntime {
    val provider = buildProvider(providerType, apiKeyOption, baseUrl, model)
    val registry = dev.sophi.core.tools.ToolRegistry()
    buildBuiltinTools(braveApiKey = braveApiKeyOption).forEach { registry.register(it) }
    registry.register(
        TournamentTool(
            provider = provider, model = model, contextWindowTokens = contextWindowTokens,
            sessionsDir = sessionsDir, versioningHome = versioningHome, evalsDir = evalsDir
        )
    )
    val proposalStore = dev.sophi.schedule.store.ProposalStore(scheduleDir.resolve("proposals.jsonl"))
    val runtime = Sophi.runtime {
        this.provider = provider
        this.model = model
        this.sessionsDir = sessionsDir
        contextWindowTokens(contextWindowTokens)
        toolRegistry(registry)
        agentsDir(agentsDir)
        tool(dev.sophi.schedule.tools.ProposeImprovementTool())
        plugin(dev.sophi.schedule.tools.ProposalPlugin(proposalStore))
        if (memoryHome != null) {
            this.memoryHome = memoryHome
            if (embeddingModel == null || embeddingBaseUrl == null) {
                onWarning("memory: disabled — --memory needs --embedding-model and --embedding-base-url")
            } else {
                memory(embeddingModel, embeddingBaseUrl, embeddingApiKey, embeddingDimensions, onWarning)
            }
        }
    }
    val engine = runtime.scheduleEngine(
        taskStore = dev.sophi.schedule.store.TaskStore(scheduleDir.resolve("tasks.json")),
        runLog = dev.sophi.schedule.store.RunLog(scheduleDir.resolve("runs.jsonl")),
        notifier = HubFallbackNotifier(),
        taskTimeoutMs = taskTimeoutSeconds * 1000,
        maxTokens = maxTokens
    )
    return ScheduleRuntime(engine, runtime.memoryPlugin)
}

internal fun defaultScheduleDir(): Path = Path.of(System.getProperty("user.home"), ".sophi", "schedule")
