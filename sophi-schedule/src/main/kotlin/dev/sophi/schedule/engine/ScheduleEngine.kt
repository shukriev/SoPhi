package dev.sophi.schedule.engine

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentDefinition
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.plan.LlmPlanner
import dev.sophi.core.agent.plan.LlmStepCritic
import dev.sophi.core.agent.plan.PlanFinalStatus
import dev.sophi.core.agent.plan.PlanRunner
import dev.sophi.core.agent.plan.PlanRunnerConfig
import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.turnEventBridge
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.notify.Notifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

class ScheduleEngine(
    private val taskStore: TaskStore,
    private val runLog: RunLog,
    private val provider: LLMProvider,
    private val fullRegistry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val notifier: Notifier,
    private val model: String,
    /**
     * Total context window of [model], in tokens. Especially important for unattended runs:
     * nobody is watching to notice a turn that has run itself out of context.
     */
    private val contextWindowTokens: Int,
    private val agentDefinitions: List<AgentDefinition> = emptyList(),
    private val maxConcurrentTasks: Int = 4,
    /**
     * Hard wall-clock cap on one task's run (covers every iteration for Goal mode).
     * An unattended run has no one there to notice a hang and Ctrl-C it, so a stuck
     * LLM call must fail the run instead of blocking its semaphore slot forever.
     */
    private val taskTimeoutMs: Long = 300_000,
    /**
     * Max completion tokens per turn. Reasoning models' hidden chain-of-thought counts
     * against this budget — too low and the model can hit finish_reason=length before
     * ever emitting an answer or tool call.
     */
    private val maxTokens: Int = 4096,
    private val pluginRegistry: PluginRegistry? = null
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
            withTimeout(taskTimeoutMs) {
                val session = sessionManager.create(title = "schedule:${task.name}")
                val bridge = pluginRegistry?.turnEventBridge(session.id) ?: { _: TurnEvent -> }
                val scopedRegistry = task.subagentType
                    ?.let { type -> agentDefinitions.find { it.name == type } }
                    ?.let { def -> fullRegistry.subset(def.allowedTools) }
                    ?: fullRegistry
                val loop = AgentLoop(
                    provider, scopedRegistry, sessionManager,
                    confirmationPolicy = dev.sophi.core.tools.ConfirmationPolicy.DENY_ALL,
                    grants = task.toolGrants,
                    contextWindowTokens = contextWindowTokens
                )
                val config = AgentConfig(model = model, maxTokens = maxTokens)

                val (outcome, summary) = when (val mode = task.mode) {
                    is TaskMode.Recurring -> {
                        val result = loop.turn(session, task.prompt, config, bridge)
                        RunOutcome.Succeeded to (result.tip?.content ?: "")
                    }
                    is TaskMode.Goal -> {
                        val planner = LlmPlanner(provider, model)
                        val critic = LlmStepCritic(provider, model)
                        // Scheduled runs are always unattended (DENY_ALL + per-task grants above),
                        // so overlapping confirmation prompts can never happen here — safe to
                        // parallelize independent plan steps (ADR-018).
                        val runnerConfig = PlanRunnerConfig(
                            model = model, maxTokens = maxTokens,
                            maxStepExecutions = mode.maxIterations, allowParallelSteps = true
                        )
                        val runner = PlanRunner(loop, sessionManager, provider, planner, critic, runnerConfig, onEvent = bridge)
                        val result = runner.run(session.id, task.prompt, mode.stopCondition)
                        (if (result.finalStatus == PlanFinalStatus.Met) RunOutcome.GoalMet else RunOutcome.GoalExhausted) to
                            result.finalOutput
                    }
                }
                RunRecord(task.id, startedAtMs, System.currentTimeMillis(), outcome, summary)
            }
        } catch (e: TimeoutCancellationException) {
            RunRecord(task.id, startedAtMs, System.currentTimeMillis(),
                RunOutcome.Failed("timed out after ${taskTimeoutMs / 1000}s"), "")
        } catch (e: Exception) {
            RunRecord(task.id, startedAtMs, System.currentTimeMillis(), RunOutcome.Failed(e.message ?: "unknown error"), "")
        }

        runLog.append(record)
        taskStore.recordRun(task.id, record.finishedAtMs)
        notifier.notify(task, record)
        return record
    }
}
