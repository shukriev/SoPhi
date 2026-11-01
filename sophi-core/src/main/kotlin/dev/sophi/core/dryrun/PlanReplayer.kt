package dev.sophi.core.dryrun

import dev.sophi.core.audit.AuditSink
import dev.sophi.core.audit.auditEntry
import dev.sophi.core.audit.recordQuietly
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.tools.isToolError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class PlanNotRunnableException(message: String) : Exception(message)

/**
 * Runs an approved plan's steps exactly — same tools, same arguments, in order — with no model in the loop.
 * Stops at the first failed step. The plan file is saved after every step, so a crash or a stop leaves a record.
 */
class PlanReplayer(
    private val registry: ToolRegistry,
    private val store: ChangePlanStore,
    private val audit: AuditSink? = null,
    /** Called once before the first step whose tool has reach LOCAL (EVERY mode), so /undo covers replayed local edits. */
    private val checkpoint: (suspend () -> Unit)? = null,
) {
    suspend fun run(plan: ChangePlan, onStep: suspend (StepResult) -> Unit = {}): ChangePlan {
        val steps = plan.approvedSteps()
        val results = mutableListOf<StepResult>()
        // Check and mark RUNNING under the plan's lock: two processes can't both start it.
        var state = (store.startLock(plan.id) ?: throw PlanNotRunnableException("This plan is starting elsewhere right now")).use {
            val current = store.get(plan.id) ?: plan
            if (current.status != PlanStatus.APPROVED || plan.status != PlanStatus.APPROVED) throw PlanNotRunnableException("This plan wasn't approved")
            if (plan.approvedHash != plan.stepsHash() || current.approvedHash != plan.approvedHash) {
                throw PlanNotRunnableException("This plan changed after it was approved")
            }
            // Checked before anything runs, so a plan opened where its servers aren't connected stays approved for later.
            steps.map { it.tool }.distinct().filter { registry.getOrNull(it) == null }.takeIf { it.isNotEmpty() }?.let {
                throw PlanNotRunnableException("This plan needs tools that aren't connected here: ${it.joinToString(", ")}")
            }
            plan.copy(status = PlanStatus.RUNNING, results = emptyList()).also(store::save)
        }
        fun finish(status: PlanStatus): ChangePlan {
            val rest = steps.drop(results.size).map { StepResult(it.n, "not-run", "") }
            return state.copy(status = status, results = results + rest).also(store::save)
        }
        var checkpointed = false
        try {
            for (step in steps) {
                val tool = registry.getOrNull(step.tool)
                if (!checkpointed && tool?.reach(step.args) == Reach.LOCAL && checkpoint != null) {
                    checkpoint.invoke(); checkpointed = true
                }
                val start = System.currentTimeMillis()
                val result = if (tool == null) "Error: Tool '${step.tool}' not found" else try {
                    tool.execute(step.args)
                } catch (e: CancellationException) {
                    // Stopped mid-call: the request may already have reached the other system.
                    results += StepResult(step.n, "cancelled", "Stopped while running; it may have run")
                    audit?.recordQuietly {
                        auditEntry(plan.actor, plan.tenant, plan.sessionId, step.tool, step.risk, step.args, "plan",
                            "cancelled", "", start, plan = "${plan.id}#${step.n}")
                    }
                    throw e
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                val ok = !isToolError(result)
                audit?.recordQuietly {
                    auditEntry(plan.actor, plan.tenant, plan.sessionId, step.tool, step.risk, step.args, "plan",
                        if (ok) "ok" else "error", result, start, plan = "${plan.id}#${step.n}")
                }
                val r = StepResult(step.n, if (ok) "ok" else "error", result)
                results += r
                state = state.copy(results = results.toList()).also(store::save)
                onStep(r)
                if (!ok) return finish(PlanStatus.FAILED)
            }
            return finish(PlanStatus.DONE)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finish(PlanStatus.CANCELLED) }
            throw e
        }
    }
}
