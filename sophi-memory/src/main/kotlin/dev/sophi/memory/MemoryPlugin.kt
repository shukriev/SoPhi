package dev.sophi.memory

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.ContextContributor
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Harness adapter (spec §3.2): recall on the turn path via ContextContributor,
 * encoding fire-and-forget on AFTER_TURN. Memory must never break a turn — every
 * path is runCatching-wrapped; encode failures lose at most one turn's memories.
 */
class MemoryPlugin(
    val technique: MemoryTechnique,
    private val clock: () -> Long = System::currentTimeMillis
) : SophiPlugin, ContextContributor {
    override val name = "memory"

    private val encodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = CopyOnWriteArrayList<Job>()

    override suspend fun contribute(sessionId: String, userInput: String): String? =
        runCatching { technique.recall(RecallQuery(sessionId, userInput, clock()))?.rendered }.getOrNull()

    override fun hooks(): List<AgentHook> = listOf(object : AgentHook {
        override val point = HookPoint.AFTER_TURN
        override suspend fun invoke(context: HookContext) {
            val input = context.userInput ?: return
            val reply = context.assistantReply ?: return
            val turn = TurnObservation(context.sessionId, input, reply, clock(), ambient = context.ambient)
            val job = encodeScope.launch { runCatching { technique.observe(turn) } }
            inFlight += job
            job.invokeOnCompletion { inFlight -= job }
        }
    })

    /**
     * Attributes [success] to every memory recalled during [sessionId] (see
     * [dev.sophi.memory.jane.JanesPalace.recordOutcome]) when the underlying technique is a
     * JanesPalace; a silent no-op for any other [MemoryTechnique], same as [palace] itself.
     * Memory must never break a caller's session-end housekeeping, so failures are swallowed.
     */
    fun recordSessionEnd(sessionId: String, success: Boolean) {
        runCatching { palace()?.recordOutcome(sessionId, success) }
    }

    /** Session-end housekeeping: run the sleep cycle when >24h since the last one. */
    suspend fun consolidateIfDue(): ConsolidationReport? {
        val palace = technique as? dev.sophi.memory.jane.JanesPalace ?: return null
        val now = clock()
        return if (palace.consolidationDue(now)) runCatching { technique.consolidate(now) }.getOrNull()
        else null
    }

    /** Test hook: await all in-flight encodes. */
    suspend fun drainEncodes() { inFlight.toList().forEach { it.join() } }

    /** The palace-specific read surface (browse/threads/profileView), or null for any other technique. */
    fun palace(): dev.sophi.memory.jane.JanesPalace? = technique as? dev.sophi.memory.jane.JanesPalace

    fun close() {
        encodeScope.cancel()
        (technique as? dev.sophi.memory.jane.JanesPalace)?.close()
    }
}
