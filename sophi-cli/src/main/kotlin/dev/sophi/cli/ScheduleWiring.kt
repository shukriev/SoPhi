package dev.sophi.cli

import dev.sophi.schedule.engine.ScheduleEngine
import dev.sophi.sdk.Sophi
import dev.sophi.sdk.buildBuiltinTools
import java.nio.file.Path

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
    maxTokens: Int = 4096
): ScheduleEngine {
    val provider = buildProvider(providerType, apiKeyOption, baseUrl, model)
    val registry = dev.sophi.core.tools.ToolRegistry()
    buildBuiltinTools(braveApiKey = braveApiKeyOption).forEach { registry.register(it) }
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
    }
    return runtime.scheduleEngine(
        taskStore = dev.sophi.schedule.store.TaskStore(scheduleDir.resolve("tasks.json")),
        runLog = dev.sophi.schedule.store.RunLog(scheduleDir.resolve("runs.jsonl")),
        notifier = HubFallbackNotifier(),
        taskTimeoutMs = taskTimeoutSeconds * 1000,
        maxTokens = maxTokens
    )
}

internal fun defaultScheduleDir(): Path = Path.of(System.getProperty("user.home"), ".sophi", "schedule")
