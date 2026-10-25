package dev.sophi.core.undo

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable
data class FileMove(val from: String, val to: String)

/** Moves made by one tool round. A nested loop's element has [parent], so a subagent's moves reach the outer round too. */
class RoundChanges(private val parent: RoundChanges? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RoundChanges>

    // Tools of one round run in parallel asyncs.
    private val recorded = CopyOnWriteArrayList<FileMove>()
    val moves: List<FileMove> get() = recorded.toList()

    fun record(move: FileMove) {
        recorded.add(move)
        parent?.record(move)
    }
}

/**
 * Runs [block] as one round of [session] for /undo, when its tools run in nested loops on other sessions (a /goal or /plan
 * run): snapshots first, and appends one replay=false entry with the `checkpoint`/`moves` metadata AgentLoop writes, even
 * when [block] throws. Appends nothing when there is neither. The caller saves the session.
 */
suspend fun <T> undoableRound(session: AgentSession, checkpoints: CheckpointStore?, label: String, block: suspend () -> T): T {
    val start = System.currentTimeMillis()
    // a checkpoint failure never blocks the run
    val meta = checkpoints?.let { runCatching { it.snapshot() }.getOrNull() }?.let { mapOf("checkpoint" to it, "checkpointMs" to "${System.currentTimeMillis() - start}") }.orEmpty()
    val changes = RoundChanges(currentCoroutineContext()[RoundChanges])
    try {
        return withContext(changes) { block() }
    } finally {
        val moves = changes.moves.takeIf { it.isNotEmpty() }?.let { mapOf("moves" to Json.encodeToString(it)) }.orEmpty()
        if (meta.isNotEmpty() || moves.isNotEmpty()) session.append(EntryRole.ASSISTANT, "[$label file changes]", mapOf("replay" to "false") + meta + moves)
    }
}
