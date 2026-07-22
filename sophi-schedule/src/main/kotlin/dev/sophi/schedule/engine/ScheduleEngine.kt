package dev.sophi.schedule.engine

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentDefinition
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.notify.Notifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ScheduleEngine(
    private val taskStore: TaskStore,
    private val runLog: RunLog,
    private val provider: LLMProvider,
    private val fullRegistry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val notifier: Notifier,
    private val model: String,
    private val agentDefinitions: List<AgentDefinition> = emptyList(),
    private val maxConcurrentTasks: Int = 4
) {
    suspend fun tickOnce(nowMs: Long = System.currentTimeMillis()) {
        val due = taskStore.list().filter { it.enabled && it.nextRunAtMs != null && it.nextRunAtMs <= nowMs }
        val semaphore = Semaphore(maxConcurrentTasks)
        coroutineScope {
            due.map { task -> async { semaphore.withPermit { runTask(task) } } }.awaitAll()
        }
    }

    suspend fun runNow(taskId: String): RunRecord? {
        val task = taskStore.get(taskId) ?: return null
        return runTask(task)
    }

    private suspend fun runTask(task: ScheduledTask): RunRecord {
        val startedAtMs = System.currentTimeMillis()
        val record = try {
            val session = sessionManager.create(title = "schedule:${task.name}")
            val scopedRegistry = task.subagentType
                ?.let { type -> agentDefinitions.find { it.name == type } }
                ?.let { def -> fullRegistry.subset(def.allowedTools) }
                ?: fullRegistry
            val confirmationPolicy = AllowlistConfirmationPolicy(task.destructiveToolAllowlist)
            val loop = AgentLoop(provider, scopedRegistry, sessionManager, confirmationPolicy = confirmationPolicy)
            val config = AgentConfig(model = model)

            val (outcome, summary) = when (val mode = task.mode) {
                is TaskMode.Recurring -> {
                    val result = loop.turn(session, task.prompt, config)
                    RunOutcome.Succeeded to (result.tip?.content ?: "")
                }
                is TaskMode.Goal -> {
                    val runner = GoalRunner(loop, provider, model)
                    val result = runner.run(session, task.prompt, config, mode.stopCondition, mode.maxIterations)
                    (if (result.met) RunOutcome.GoalMet else RunOutcome.GoalExhausted) to result.lastOutput
                }
            }
            RunRecord(task.id, startedAtMs, System.currentTimeMillis(), outcome, summary)
        } catch (e: Exception) {
            RunRecord(task.id, startedAtMs, System.currentTimeMillis(), RunOutcome.Failed(e.message ?: "unknown error"), "")
        }

        runLog.append(record)
        taskStore.recordRun(task.id, record.finishedAtMs)
        notifier.notify(task, record)
        return record
    }
}
