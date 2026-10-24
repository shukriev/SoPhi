package dev.sophi.core.undo

import kotlinx.serialization.Serializable
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
