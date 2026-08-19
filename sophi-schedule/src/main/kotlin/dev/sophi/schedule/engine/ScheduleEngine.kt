package dev.sophi.schedule.engine

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentDefinition
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.plan.LlmPlanCritic
import dev.sophi.core.agent.plan.LlmPlanner
import dev.sophi.core.agent.plan.LlmStepCritic
import dev.sophi.core.agent.plan.PlanFinalStatus
import dev.sophi.core.agent.plan.PlanRunner
import dev.sophi.core.agent.plan.PlanRunnerConfig
import dev.sophi.core.agent.plan.TreePlanner
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
import kotlin.time.Duration.Companion.seconds

/** Env kill switch for the ToT replan search — see [planSearchTemperatures] (ADR-024). */
internal const val TOT_SEARCH_ENABLED_ENV = "SOPHI_TOT_SEARCH_ENABLED"

/**
 * Candidate temperatures for TreePlanner's replan search (ADR-024, on probation). LlmPlanner is
 * deterministic at 0.0, so distinct temperatures are what make the k candidate tails actually
 * differ — TreePlanner has no diversity mechanism of its own.
 *
 * Setting SOPHI_TOT_SEARCH_ENABLED=false (or 0) collapses this to listOf(0.0), which TreePlanner
 * short-circuits on a single delegate — byte-identical pre-search behaviour at zero extra cost,
 * with no change needed inside TreePlanner. A feature on probation has to be killable without a
 * code deploy, and this is also how the A/B's baseline arm was run.
 *
 * Fails safe toward ON: only an explicit false/0 disables. A typo or stray value must not
 * silently switch off the very thing a probation review is measuring.
 *
 * [env] is injectable for testing only — System.getenv cannot be mutated in-process.
 */
internal fun planSearchTemperatures(env: (String) -> String? = System::getenv): List<Double> =
    if (env(TOT_SEARCH_ENABLED_ENV)?.lowercase() in setOf("false", "0")) listOf(0.0)
    else listOf(0.0, 0.7, 1.0)

/**
 * Scoring budget for TreePlanner's candidate tails. Deliberately far above LlmPlanCritic's 30s
 * default, which is too tight for this call: a local reasoning model measured 166s to emit a
 * single score (probe, 2026-08-16, qwen3.5:9b). That matters more here than for LlmStepCritic,
 * which shares the 30s default — StepCritic failing open degrades to a safe assumption, whereas
 * PlanCritic failing open makes every candidate tie at 1.0, so maxBy returns delegates[0] and
 * the search silently collapses to pre-search behavior while still paying for every extra
 * planner and critic call. A no-op that costs full price is the one outcome worth engineering
 * against.
 */
private val PLAN_CRITIC_TIMEOUT = 300.seconds

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
    /** Applied to every task's [AgentConfig]; the caller builds the full text. */
    private val systemPrompt: String? = null,
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
        var sessionId: String? = null
        val record = if (wallClockBudgetExceeded(task, startedAtMs)) {
            RunRecord(
                task.id, startedAtMs, System.currentTimeMillis(),
                RunOutcome.Failed(
                    "wall-clock budget exceeded (${task.maxWallClockMsPerWindow}ms used within " +
                        "the trailing ${task.wallClockWindowMs}ms window)"
                ), ""
            )
        } else try {
            withTimeout(taskTimeoutMs) {
                val session = sessionManager.create(title = "schedule:${task.name}")
                sessionId = session.id
                val bridge = pluginRegistry?.turnEventBridge(session.id) ?: { _: TurnEvent -> }
                val scopedRegistry = when (val type = task.subagentType) {
                    null -> fullRegistry
                    else -> {
                        val def = agentDefinitions.find { it.name == type }
                            ?: throw IllegalStateException(
                                "Scheduled task '${task.name}' (${task.id}) references unknown subagentType '$type'"
                            )
                        fullRegistry.subset(def.allowedTools)
                    }
                }
                val loop = AgentLoop(
                    provider, scopedRegistry, sessionManager,
                    confirmationPolicy = dev.sophi.core.tools.ConfirmationPolicy.DENY_ALL,
                    grants = task.toolGrants,
                    contextWindowTokens = contextWindowTokens
                )
                val config = AgentConfig(model = model, maxTokens = maxTokens, systemPrompt = systemPrompt)

                // Null unless a plan actually ran — see RunRecord.replans on why null and 0 must
                // stay distinguishable.
                var replans: Int? = null
                var decompositions: Int? = null

                val (outcome, summary) = when (val mode = task.mode) {
                    is TaskMode.Recurring -> {
                        val result = loop.turn(session, task.prompt, config, bridge)
                        RunOutcome.Succeeded to (result.tip?.content ?: "")
                    }
                    is TaskMode.Goal -> {
                        val planner = TreePlanner(
                            delegates = planSearchTemperatures().map {
                                LlmPlanner(provider, model, temperature = it)
                            },
                            critic = LlmPlanCritic(provider, model, timeout = PLAN_CRITIC_TIMEOUT)
                        )
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
                        replans = result.replans.size
                        decompositions = result.decompositions.size
                        (if (result.finalStatus == PlanFinalStatus.Met) RunOutcome.GoalMet else RunOutcome.GoalExhausted) to
                            result.finalOutput
                    }
                }
                RunRecord(
                    task.id, startedAtMs, System.currentTimeMillis(), outcome, summary,
                    replans = replans, decompositions = decompositions, sessionId = sessionId
                )
            }
        } catch (e: TimeoutCancellationException) {
            RunRecord(task.id, startedAtMs, System.currentTimeMillis(),
                RunOutcome.Failed("timed out after ${taskTimeoutMs / 1000}s"), "", sessionId = sessionId)
        } catch (e: Exception) {
            RunRecord(task.id, startedAtMs, System.currentTimeMillis(),
                RunOutcome.Failed(e.message ?: "unknown error"), "", sessionId = sessionId)
        }

        runLog.append(record)
        taskStore.recordRun(task.id, record.finishedAtMs)
        notifier.notify(task, record)
        return record
    }

    /**
     * True when [task]'s own past runs, summed within the trailing [ScheduledTask.wallClockWindowMs]
     * ending at [nowMs], already meet or exceed [ScheduledTask.maxWallClockMsPerWindow]. Always
     * false when that budget is unset (the default) — every existing task is unaffected. Derived
     * entirely from RunLog's existing startedAtMs/finishedAtMs; no new persistence.
     */
    private fun wallClockBudgetExceeded(task: ScheduledTask, nowMs: Long): Boolean {
        val budget = task.maxWallClockMsPerWindow ?: return false
        val windowStart = nowMs - task.wallClockWindowMs
        val usedMs = runLog.forTask(task.id)
            .filter { it.startedAtMs >= windowStart }
            .sumOf { it.finishedAtMs - it.startedAtMs }
        return usedMs >= budget
    }
}
