package dev.sophi.core.undo

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.sophi.core.tools.bashReachesOutside
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable
data class FileMove(val from: String, val to: String)

/**
 * Moves and allowed bash commands of one tool round. A nested loop's element has [parent], so a subagent's moves and
 * commands reach the outer round too; whoever knows the workspace root picks the commands that reach outside it.
 */
class RoundChanges(private val parent: RoundChanges? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RoundChanges>

    // Tools of one round run in parallel asyncs.
    private val recorded = CopyOnWriteArrayList<FileMove>()
    val moves: List<FileMove> get() = recorded.toList()
    private val bash = CopyOnWriteArrayList<String>()
    val commands: List<String> get() = bash.toList()

    fun record(move: FileMove) {
        recorded.add(move)
        parent?.record(move)
    }

    fun recordCommand(command: String) {
        bash.add(command)
        parent?.recordCommand(command)
    }

    /** The recorded bash commands that reach outside [root]. */
    fun outsideCommands(root: Path): List<String> = commands.filter { bashReachesOutside(it, root) != null }
}

/**
 * Runs [block] as one round of [session] for /undo, when its tools run in nested loops on other sessions (a /goal or /plan
 * run): snapshots first, and appends one replay=false entry with the `checkpoint`/`moves` metadata AgentLoop writes, even
 * when [block] throws, plus `outsideCommands` from the store's root. Appends nothing when the round changed no files and
 * reached nowhere outside. The caller saves the session.
 */
suspend fun <T> undoableRound(session: AgentSession, checkpoints: CheckpointStore?, label: String, block: suspend () -> T): T {
    val start = System.currentTimeMillis()
    // a checkpoint failure never blocks the run
    val checkpoint = checkpoints?.let { runCatching { it.snapshot() }.getOrNull() }
    val ms = System.currentTimeMillis() - start
    val changes = RoundChanges(currentCoroutineContext()[RoundChanges])
    try {
        return withContext(changes) { block() }
    } finally {
        val moves = changes.moves
        val outside = checkpoints?.let { changes.outsideCommands(it.root) }.orEmpty()
        // an unchanged workspace snapshots to the same sha
        val changed = checkpoint != null && runCatching { checkpoints.snapshot() }.getOrNull() != checkpoint
        if (changed || moves.isNotEmpty() || outside.isNotEmpty()) session.append(EntryRole.ASSISTANT, "[$label file changes]", buildMap {
            put("replay", "false")
            if (checkpoint != null) { put("checkpoint", checkpoint); put("checkpointMs", "$ms") }
            if (moves.isNotEmpty()) put("moves", Json.encodeToString(moves))
            if (outside.isNotEmpty()) put("outsideCommands", Json.encodeToString(outside))
        })
    }
}
