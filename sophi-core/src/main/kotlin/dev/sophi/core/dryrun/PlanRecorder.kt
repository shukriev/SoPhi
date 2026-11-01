package dev.sophi.core.dryrun

import dev.sophi.core.audit.LOCAL_ACTOR
import dev.sophi.core.tools.RiskLevel
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Present in a turn's coroutine context = the turn is plan-first: the agent loop records the changes [mode] covers
 * instead of running them, and notes the lookups that ran. Nested loops (subagents, decompose_goal) inherit it.
 */
class PlanRecorder(val mode: PlanFirst, val trustReadOnlyHints: Boolean = true) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PlanRecorder>

    private val lock = Any()
    private val _steps = mutableListOf<PlanStep>()
    private val _reads = mutableListOf<PlanRead>()

    val steps: List<PlanStep> get() = synchronized(lock) { _steps.toList() }
    val reads: List<PlanRead> get() = synchronized(lock) { _reads.toList() }

    /** Records a change and returns its step number, from 1. */
    fun record(tool: String, args: String, risk: RiskLevel, allowedByRule: Boolean): Int = synchronized(lock) {
        val n = _steps.size + 1
        _steps += PlanStep(n, tool, args, risk, allowedByRule)
        n
    }

    fun read(tool: String, args: String, result: String, viaHint: Boolean) = synchronized(lock) {
        _reads += PlanRead(tool, args, result, viaHint)
    }

    fun toPlan(task: String, sessionId: String, actor: String = LOCAL_ACTOR, tenant: String? = null): ChangePlan =
        ChangePlan("plan_" + UUID.randomUUID(), System.currentTimeMillis(), task, sessionId, actor, tenant, reads, steps)
}
