package dev.sophi.core.tools

import kotlinx.coroutines.Job
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Counts tool calls blocked during an unattended run (ScheduleEngine sets it); the [max]th cancels [job],
 * so a run nobody can approve stops instead of retrying what it will never be allowed to do.
 */
class DenialLimit(private val job: Job, val max: Int = 3) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DenialLimit>

    private val blocked = CopyOnWriteArrayList<String>()
    val tripped: Boolean get() = blocked.size >= max

    fun denied(tool: String) {
        blocked += tool
        if (tripped) job.cancel()
    }

    fun reason(): String =
        "Stopped after $max blocked actions: ${blocked.distinct().joinToString(", ")}. Allow these tools on the goal to let it do them."
}
