package dev.sophi.core.tools

import dev.sophi.core.audit.UNREDACTABLE
import dev.sophi.core.audit.redactArguments
import kotlinx.coroutines.Job
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Counts tool calls blocked during an unattended run (ScheduleEngine sets it); the [max]th cancels [job],
 * so a run nobody can approve stops instead of retrying what it will never be allowed to do.
 */
class DenialLimit(private val job: Job, val max: Int = 3) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DenialLimit> {
        private const val MAX_ARGS = 80
    }

    private val blocked = CopyOnWriteArrayList<String>()
    val tripped: Boolean get() = blocked.size >= max

    fun denied(tool: String, argumentsJson: String = "{}") {
        val args = runCatching { redactArguments(argumentsJson, budget = MAX_ARGS) }.getOrDefault(UNREDACTABLE).trim()
        blocked += if (args == "{}" || args.isEmpty()) tool else "$tool ${if (args.length > MAX_ARGS) args.take(MAX_ARGS) + "…" else args}"
        if (tripped) job.cancel()
    }

    /** The blocked calls (tool and short arguments, secrets redacted), for the run's report. */
    fun reason(): String =
        "Stopped after $max blocked actions: ${blocked.distinct().joinToString(", ")}. Allow these tools on the goal to let it do them."
}
