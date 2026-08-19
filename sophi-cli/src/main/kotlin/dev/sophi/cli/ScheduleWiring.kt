package dev.sophi.cli

import dev.sophi.core.agent.AgentDefinition
import dev.sophi.core.agent.AgentDefinitionLoader
import dev.sophi.core.session.FileSessionManager
import dev.sophi.schedule.engine.ScheduleEngine
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import dev.sophi.sdk.DefaultPrompt
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
    contextWindowTokens: Int,
    taskTimeoutSeconds: Long = 300,
    maxTokens: Int = 4096
): ScheduleEngine {
    val provider = buildProvider(providerType, apiKeyOption, baseUrl, model)
    val registry = dev.sophi.core.tools.ToolRegistry()
    buildBuiltinTools(braveApiKeyOption).forEach { registry.register(it) }
    val sessionManager = FileSessionManager(sessionsDir)
    val agentDefinitions = loadAgentDefinitionsOrWarn(agentsDir)
    val notifier = HubFallbackNotifier()
    return ScheduleEngine(
        taskStore = TaskStore(scheduleDir.resolve("tasks.json")),
        runLog = RunLog(scheduleDir.resolve("runs.jsonl")),
        provider = provider,
        fullRegistry = registry,
        sessionManager = sessionManager,
        notifier = notifier,
        model = model,
        contextWindowTokens = contextWindowTokens,
        agentDefinitions = agentDefinitions,
        taskTimeoutMs = taskTimeoutSeconds * 1000,
        maxTokens = maxTokens,
        systemPrompt = "${DefaultPrompt.BASE}\n\n${DefaultPrompt.UNATTENDED}"
    )
}

internal fun defaultScheduleDir(): Path = Path.of(System.getProperty("user.home"), ".sophi", "schedule")

/**
 * A malformed agent-definition file used to silently collapse the whole allowlist to an empty
 * list (AgentDefinitionLoader.load throws on the first bad file, and the old runCatching here
 * swallowed it). Now that ScheduleEngine fails closed on an unresolved subagentType, that
 * silence would otherwise surface only later, as a run failure per scheduled task — this warns
 * at startup instead, when an operator can actually see it.
 */
internal fun loadAgentDefinitionsOrWarn(agentsDir: Path): List<AgentDefinition> =
    runCatching {
        AgentDefinitionLoader().load(agentsDir.also { it.createDirectories() })
    }.getOrElse { e ->
        System.err.println("Warning: failed to load agent definitions from $agentsDir: ${e.message}")
        emptyList()
    }
