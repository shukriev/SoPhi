package dev.sophi.core.audit

import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Which rule decided each call of one confirmation batch. AgentLoop installs it around confirm(); policies write to it. */
class DecisionNotes : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DecisionNotes>

    private val notes = ConcurrentHashMap<String, String>()
    val rules: Map<String, String> get() = notes

    /** Thread-safe; the first note for a callId wins, so a wrapping policy can't overwrite the one that decided. */
    fun note(callId: String, rule: String) {
        notes.putIfAbsent(callId, rule)
    }
}

/** No-op without [DecisionNotes] in context. */
suspend fun noteDecision(callId: String, rule: String) {
    currentCoroutineContext()[DecisionNotes]?.note(callId, rule)
}

/** The audit sink of the outermost loop, so nested loops (subagents, plan steps) audit to the same place. */
class AuditScope(val sink: AuditSink) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AuditScope>
}
