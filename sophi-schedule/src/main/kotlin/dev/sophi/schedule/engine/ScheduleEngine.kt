package dev.sophi.schedule.engine

import dev.sophi.core.dryrun.ChangePlan
import dev.sophi.core.dryrun.PlanFirst
import dev.sophi.core.dryrun.PlanRecorder
import dev.sophi.core.dryrun.PlanStatus
import dev.sophi.core.dryrun.replayReport
import kotlin.coroutines.EmptyCoroutineContext
import dev.sophi.core.tools.DenialLimit

import dev.sophi.ai.api.LLMProvider
import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.agent.AgentDefinition
import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.agent.plan.LlmPlanCritic
import dev.sophi.core.agent.plan.LlmPlanner
import dev.sophi.core.agent.plan.LlmStepCritic
import dev.sophi.core.agent.plan.PlanFinalStatus
import dev.sophi.core.agent.plan.PlanLog
import dev.sophi.core.agent.plan.PlanRunner
import dev.sophi.core.agent.plan.PlanRunnerConfig
import dev.sophi.core.agent.plan.StepCritic
import dev.sophi.core.agent.plan.TreePlanner
import dev.sophi.core.audit.AuditSink
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.PluginRegistry
import dev.sophi.schedule.model.RunOutcome
import dev.sophi.schedule.model.RunRecord
import dev.sophi.schedule.model.ScheduledTask
import dev.sophi.schedule.model.TaskMode
import dev.sophi.schedule.notify.Notifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

/** This many runs in a row failing with the same [causeKey] pause the task until the user resumes it. */
internal const val FAILURE_STREAK = 3

/**
 * "Fails the same way": the error's first line with every digit-bearing token (counts, ports,
 * durations, hex ids) collapsed, so "timed out after 300s" and "...120s" match.
 * ponytail: string normalisation, not a classifier; two different causes with the same first line merge.
 */
internal fun causeKey(error: String): String =
    error.lineSequence().first().lowercase().replace(Regex("[0-9a-f]*[0-9][0-9a-f]*"), "#").trim()

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
    private val pluginRegistry: PluginRegistry? = null,
    /** false skips Goal-mode's step critic LLM call entirely — a HarnessConfig knob (Task 14+),
     *  defaulted on so no existing caller needs updating. */
    private val criticEnabled: Boolean = true,
    /** Persists every Goal-mode run's plan versions, or null to skip persistence entirely
     *  (matches every existing caller's behavior — no live observation here, only history:
     *  scheduled runs use allowParallelSteps = true, which the chat path's live-attachment
     *  logic doesn't support). */
    private val planLog: PlanLog? = null,
    /** Where each task's tool calls are audited, or null to skip auditing. */
    private val audit: AuditSink? = null,
    /** Null inherits no rules: an unattended run outside a turn enforces nothing unless the host passes them. */
    private val permissions: dev.sophi.core.permissions.PermissionRules? = null,
    /** Replays an approved change plan (a task's [ScheduledTask.changePlanId]); null = this scheduler can't. */
    private val runChangePlan: (suspend (planId: String) -> ChangePlan)? = null,
    /** Plan-first for model runs: changes are recorded into a pending plan ([savePendingPlan]) instead of made. */
    private val planFirst: PlanFirst = PlanFirst.OFF,
    private val trustReadOnlyHints: Boolean = true,
    private val savePendingPlan: ((ChangePlan) -> Unit)? = null,
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
        val record = if (task.pausedReason != null) {
            RunRecord(task.id, startedAtMs, startedAtMs,
                RunOutcome.Skipped("paused after $FAILURE_STREAK failed runs: ${task.pausedReason} (resume the task to run it again)"), "")
        } else if (wallClockBudgetExceeded(task, startedAtMs)) {
            RunRecord(
                task.id, startedAtMs, System.currentTimeMillis(),
                RunOutcome.Skipped(
                    "wall-clock budget exceeded (${task.maxWallClockMsPerWindow}ms used within " +
                        "the trailing ${task.wallClockWindowMs}ms window)"
                ), ""
            )
        } else try {
            withTimeout(taskTimeoutMs) {
                task.changePlanId?.let { return@withTimeout replayChangePlan(task, it, startedAtMs) }
                (task.mode as? TaskMode.Watch)?.let { return@withTimeout checkPage(task, it.url, startedAtMs) }
                val session = sessionManager.create(title = "schedule:${task.name}")
                val recorder = if (planFirst != PlanFirst.OFF && savePendingPlan != null) PlanRecorder(planFirst, trustReadOnlyHints) else null
                sessionId = session.id
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
                val loop = loopFor(task, scopedRegistry)
                val config = AgentConfig(model = model, maxTokens = maxTokens, systemPrompt = systemPrompt)
                // Same collectContext path every interactive entry point already uses (SophiRuntime.
                // streamTurn, AgentController.configWithContext) — without this, scheduled tasks
                // (including the self-improvement orchestrator) ran with zero lesson/memory context.
                val pluginContext = pluginRegistry?.collectContext(session.id, task.prompt) ?: emptyList()

                // Null unless a plan actually ran — see RunRecord.replans on why null and 0 must
                // stay distinguishable.
                var replans: Int? = null
                var decompositions: Int? = null
                var planId: String? = null

                // SessionIdContext scopes only the actual turn/plan-run call below — SubagentTool and
                // DecomposeGoalTool are the only readers, and everything else in this function (config
                // assembly, RunRecord building) has no use for it.
                // Nobody can approve a blocked call here, so the 3rd one stops the run (DenialLimit cancels this scope).
                var limit: DenialLimit? = null
                val ran = try {
                    coroutineScope { withContext(DenialLimit(coroutineContext.job).also { limit = it }) { when (val mode = task.mode) {
                    is TaskMode.Recurring -> {
                        val extra = pluginContext.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
                        val effectiveConfig = if (extra == null) config
                            else config.copy(systemPrompt = listOfNotNull(config.systemPrompt, extra).joinToString("\n\n"))
                        val result = withContext(SessionIdContext(session.id) + dev.sophi.core.tools.Unattended + (recorder ?: EmptyCoroutineContext)) {
                            loop.turn(session, task.prompt, effectiveConfig)
                        }
                        RunOutcome.Succeeded to (result.tip?.content ?: "")
                    }
                    is TaskMode.Goal -> {
                        val planner = TreePlanner(
                            delegates = planSearchTemperatures().map {
                                LlmPlanner(provider, model, temperature = it)
                            },
                            critic = LlmPlanCritic(provider, model, timeout = PLAN_CRITIC_TIMEOUT)
                        )
                        val critic = if (criticEnabled) LlmStepCritic(provider, model) else StepCritic.ALWAYS_FULL_CONFIDENCE
                        // Scheduled runs are always unattended (DENY_ALL + per-task grants above),
                        // so overlapping confirmation prompts can never happen here — safe to
                        // parallelize independent plan steps (ADR-018).
                        val runnerConfig = PlanRunnerConfig(
                            model = model, maxTokens = maxTokens,
                            maxStepExecutions = mode.maxIterations, allowParallelSteps = true,
                            criticEnabled = criticEnabled
                        )
                        val runner = PlanRunner(loop, sessionManager, provider, planner, critic, runnerConfig, planLog = planLog)
                        val result = withContext(SessionIdContext(session.id) + dev.sophi.core.tools.Unattended + (recorder ?: EmptyCoroutineContext)) {
                            runner.run(session.id, task.prompt, mode.stopCondition, context = pluginContext)
                        }
                        replans = result.replans.size
                        decompositions = result.decompositions.size
                        planId = result.planId
                        (if (result.finalStatus == PlanFinalStatus.Met) RunOutcome.GoalMet else RunOutcome.GoalExhausted) to
                            result.finalOutput
                    }
                    is TaskMode.Watch -> error("watch runs return before a session is created")
                } } }
                } catch (e: CancellationException) {
                    if (limit?.tripped != true) throw e
                    null
                }
                val (outcome, ranSummary) = limit?.takeIf { it.tripped }?.reason()?.let { RunOutcome.Failed(it) to it } ?: ran!!
                val pending = recorder?.takeIf { it.steps.isNotEmpty() }?.toPlan(task.prompt, session.id)
                    ?.also { savePendingPlan?.invoke(it) }
                val summary = pending?.let {
                    "Plan waiting for your approval: ${it.steps.size} change${if (it.steps.size == 1) "" else "s"} — sophi plans show ${it.id}"
                } ?: ranSummary
                runCatching {
                    pluginRegistry?.dispatch(
                        HookPoint.AFTER_TURN,
                        HookContext(sessionId = session.id, userInput = task.prompt, assistantReply = summary)
                    )
                }
                RunRecord(
                    task.id, startedAtMs, System.currentTimeMillis(), outcome, summary,
                    replans = replans, decompositions = decompositions, sessionId = sessionId, planId = planId,
                    pendingPlanId = pending?.id
                )
            }
        } catch (e: TimeoutCancellationException) {
            RunRecord(task.id, startedAtMs, System.currentTimeMillis(),
                RunOutcome.Failed("timed out after ${taskTimeoutMs / 1000}s"), "", sessionId = sessionId)
        } catch (e: Exception) {
            RunRecord(task.id, startedAtMs, System.currentTimeMillis(),
                RunOutcome.Failed(e.message ?: "unknown error"), "", sessionId = sessionId)
        }

        // A watch blocked by a rule stays blocked every run: say so on the first skip in a row only.
        val repeatSkip = task.mode is TaskMode.Watch && record.outcome is RunOutcome.Skipped &&
            runLog.forTask(task.id).lastOrNull()?.outcome is RunOutcome.Skipped
        val pauseFor = (record.outcome as? RunOutcome.Failed)?.error?.takeIf { failingTheSameWay(task.id, it) }
            ?.lineSequence()?.first()
        val logged = if (pauseFor == null) record else record.copy(summary = listOf(record.summary,
            "Paused: $FAILURE_STREAK runs in a row failed the same way. Fix the cause, then resume the task.").filter { it.isNotBlank() }.joinToString("\n"))
        runLog.append(logged)
        taskStore.recordRun(task.id, logged.finishedAtMs)
        if (pauseFor != null) taskStore.update(task.id) { it.copy(enabled = false, pausedReason = pauseFor) }
        if (!repeatSkip && !logged.summary.trim().equals("NO_RESCUE_NEEDED", ignoreCase = true) &&
            !(task.mode is TaskMode.Watch && logged.summary.startsWith(WATCH_UNCHANGED))) notifier.notify(task, logged)
        return logged
    }

    /** True when this failure and the task's previous [FAILURE_STREAK]-1 non-skipped runs all failed with the same [causeKey]. */
    private fun failingTheSameWay(taskId: String, error: String): Boolean {
        val previous = runLog.forTask(taskId).filter { it.outcome !is RunOutcome.Skipped }.takeLast(FAILURE_STREAK - 1)
        return previous.size == FAILURE_STREAK - 1 &&
            previous.all { (it.outcome as? RunOutcome.Failed)?.error?.let(::causeKey) == causeKey(error) }
    }

    /** The loop a task's calls run through: nobody to ask, the task's grants and rules, the host's audit and hooks. */
    private fun loopFor(task: ScheduledTask, registry: ToolRegistry) = AgentLoop(
        provider, registry, sessionManager,
        confirmationPolicy = dev.sophi.core.tools.ConfirmationPolicy.DENY_ALL,
        grants = task.toolGrants,
        contextWindowTokens = contextWindowTokens,
        audit = audit, permissions = taskRules(task, permissions), hooks = pluginRegistry
    )

    /**
     * A watch run: fetch_url through the same pipeline as an unattended model call (hooks, rules, grants, guardian,
     * audit; AgentLoop.callTool), then a plain text compare with the snapshot under the run log's `watch/` dir.
     * No model sees the page.
     */
    private suspend fun checkPage(task: ScheduledTask, url: String, startedAtMs: Long): RunRecord {
        fun record(outcome: RunOutcome, summary: String = "") = RunRecord(task.id, startedAtMs, System.currentTimeMillis(), outcome, summary)
        if (fullRegistry.getOrNull("fetch_url") == null) return record(RunOutcome.Failed("fetch_url is not available to the scheduler"))
        val args = kotlinx.serialization.json.buildJsonObject { put("url", kotlinx.serialization.json.JsonPrimitive(url)) }.toString()
        // Not saved anywhere: it only names the call in the audit log and to hooks.
        val (result, denied) = withContext(dev.sophi.core.tools.Unattended) {
            loopFor(task, fullRegistry).callTool(dev.sophi.core.session.AgentSession("schedule:${task.id}"), "fetch_url", args)
        }
        if (denied) return record(RunOutcome.Skipped("fetch_url $url: ${result.removePrefix("Error: ")}"))
        if (result.startsWith("Error: ")) return record(RunOutcome.Failed(result.removePrefix("Error: ")))
        val status = result.lineSequence().first()
        if (status.split(" ").getOrNull(1)?.toIntOrNull()?.let { it in 200..299 } != true) return record(RunOutcome.Failed(status))
        val page = normalisePage(result.substringAfter("\n"))
        val snapshot = watchSnapshot(runLog, task.id)
        val old = if (java.nio.file.Files.exists(snapshot)) java.nio.file.Files.readString(snapshot) else null
        if (old == page) return record(RunOutcome.Succeeded, "$WATCH_UNCHANGED: $url")
        java.nio.file.Files.createDirectories(snapshot.parent)
        java.nio.file.Files.writeString(snapshot, page)
        return if (old == null) record(RunOutcome.Succeeded, "$WATCH_UNCHANGED: $url (first check, snapshot saved)")
            else record(RunOutcome.Succeeded, pageChange(url, old, page))
    }

    private suspend fun replayChangePlan(task: ScheduledTask, planId: String, startedAtMs: Long): RunRecord {
        fun record(outcome: RunOutcome, summary: String) = RunRecord(task.id, startedAtMs, System.currentTimeMillis(), outcome, summary)
        val run = runChangePlan ?: return record(RunOutcome.Failed("plan $planId can't run: this scheduler has no plan-first runtime"), "")
        val plan = try { run(planId) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return record(RunOutcome.Failed(e.message ?: "plan $planId failed"), "")
        }
        if (plan.status == PlanStatus.DONE) return record(RunOutcome.Succeeded, plan.replayReport())
        val why = plan.results.firstOrNull { it.outcome == "error" }?.result?.removePrefix("Error: ")
        return record(RunOutcome.Failed("plan $planId ${plan.status.name.lowercase()}" + (why?.let { ": $it" } ?: "")), plan.replayReport())
    }

    /**
     * True when [task]'s own past runs, summed within the trailing [ScheduledTask.wallClockWindowMs]
     * ending at [nowMs], already meet or exceed [ScheduledTask.maxWallClockMsPerWindow]. Always
     * false when that budget is unset (the default) — every existing task is unaffected. Derived
     * entirely from RunLog's existing startedAtMs/finishedAtMs; no new persistence.
     */
    /** The user's rules plus the task's and its subagent definition's deny and ask rules: a task can only tighten. */
    private fun taskRules(task: ScheduledTask, user: dev.sophi.core.permissions.PermissionRules?): dev.sophi.core.permissions.PermissionRules? {
        val own = task.rules + task.subagentType?.let { t -> agentDefinitions.find { it.name == t }?.rules }.orEmpty()
        return if (own.isEmpty()) user else (user ?: dev.sophi.core.permissions.PermissionRules.EMPTY).tightened(own)
    }

    private fun wallClockBudgetExceeded(task: ScheduledTask, nowMs: Long): Boolean {
        val budget = task.maxWallClockMsPerWindow ?: return false
        val windowStart = nowMs - task.wallClockWindowMs
        val usedMs = runLog.forTask(task.id)
            .filter { it.startedAtMs >= windowStart }
            .sumOf { it.finishedAtMs - it.startedAtMs }
        return usedMs >= budget
    }
}
