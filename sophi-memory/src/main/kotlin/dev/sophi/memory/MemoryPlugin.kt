package dev.sophi.memory

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.ContextContributor
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.Placement
import dev.sophi.extensions.SophiPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Harness adapter (spec §3.2): recall on the turn path via ContextContributor,
 * encoding fire-and-forget on AFTER_TURN. Memory must never break a turn — every
 * path is runCatching-wrapped; encode failures lose at most one turn's memories.
 */
class MemoryPlugin(
    val technique: MemoryTechnique,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long [close] waits for in-flight encodes before cancelling them. */
    private val drainTimeoutMs: Long = 30_000,
    /**
     * Told which memories a turn recalled, before their text goes into it (and so before the turn's
     * first model call): a host can act on where they came from, e.g. treat the chat as having read
     * outside text. If it throws, the recall is left out of the turn rather than injected unchecked.
     */
    private val onRecall: (sessionId: String, memories: List<MemoryView>) -> Unit = { _, _ -> },
) : SophiPlugin, ContextContributor {
    override val placement = Placement.VOLATILE

    override val name = "memory"

    private val encodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = CopyOnWriteArrayList<Job>()

    override suspend fun contribute(sessionId: String, userInput: String): String? = runCatching {
        val block = technique.recall(RecallQuery(sessionId, userInput, clock())) ?: return@runCatching null
        if (block.memoryIds.isNotEmpty()) onRecall(sessionId, technique.views(block.memoryIds))
        block.rendered
    }.getOrNull()

    override fun hooks(): List<AgentHook> = listOf(object : AgentHook {
        override val point = HookPoint.AFTER_TURN
        override suspend fun invoke(context: HookContext) {
            val input = context.userInput ?: return
            val reply = context.assistantReply ?: return
            val turn = TurnObservation(context.sessionId, input, reply, clock(), ambient = context.ambient, inMeeting = context.inMeeting)
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

    /** Awaits all in-flight encodes. */
    suspend fun drainEncodes() { inFlight.toList().forEach { it.join() } }

    /** The palace-specific read surface (browse/threads/profileView), or null for any other technique. */
    fun palace(): dev.sophi.memory.jane.JanesPalace? = technique as? dev.sophi.memory.jane.JanesPalace

    /** Lets in-flight encodes finish (up to [drainTimeoutMs]) first: a session usually ends right after
     *  its last turn, whose encode is still running, and cancelling it would lose that turn's memories. */
    fun close() {
        runCatching { runBlocking { withTimeoutOrNull(drainTimeoutMs) { drainEncodes() } } }
        encodeScope.cancel()
        (technique as? dev.sophi.memory.jane.JanesPalace)?.close()
    }
}
