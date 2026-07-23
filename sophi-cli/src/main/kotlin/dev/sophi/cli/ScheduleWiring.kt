package dev.sophi.cli

import dev.sophi.core.agent.AgentDefinitionLoader
import dev.sophi.core.session.FileSessionManager
import dev.sophi.schedule.engine.ScheduleEngine
import dev.sophi.schedule.notify.MacNotifier
import dev.sophi.schedule.notify.NoopNotifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import java.nio.file.Path
import kotlin.io.path.createDirectories

internal fun buildScheduleEngine(
    model: String,
    providerType: String,
    apiKeyOption: String?,
    baseUrl: String?,
    scheduleDir: Path,
    sessionsDir: Path,
    agentsDir: Path,
    braveApiKeyOption: String?,
    taskTimeoutSeconds: Long = 300,
    maxTokens: Int = 4096
): ScheduleEngine {
    val provider = buildProvider(providerType, apiKeyOption, baseUrl, model)
    val registry = dev.sophi.core.tools.ToolRegistry()
    buildBuiltinTools(braveApiKeyOption).forEach { registry.register(it) }
    val sessionManager = FileSessionManager(sessionsDir)
    val agentDefinitions = runCatching {
        AgentDefinitionLoader().load(agentsDir.also { it.createDirectories() })
    }.getOrDefault(emptyList())
    val notifier = if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true) {
        MacNotifier()
    } else {
        NoopNotifier
    }
    return ScheduleEngine(
        taskStore = TaskStore(scheduleDir.resolve("tasks.json")),
        runLog = RunLog(scheduleDir.resolve("runs.jsonl")),
        provider = provider,
        fullRegistry = registry,
        sessionManager = sessionManager,
        notifier = notifier,
        model = model,
        agentDefinitions = agentDefinitions,
        taskTimeoutMs = taskTimeoutSeconds * 1000,
        maxTokens = maxTokens
    )
}

internal fun defaultScheduleDir(): Path = Path.of(System.getProperty("user.home"), ".sophi", "schedule")
