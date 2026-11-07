package dev.sophi.core.agent

import dev.sophi.core.tools.DenialLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Subagents of one runtime running behind the conversation (`delegate_to_subagent` with `background`).
 * Lookups are scoped to the parent session; a null session is the host, which sees and stops any.
 * [close] cancels every run: none outlives the runtime.
 */
/** [closeTimeoutMs]: how long [close] waits for cancelled runs to save (a worktree run also commits its branch). */
class BackgroundSubagents(private val maxRunning: Int = 3, private val closeTimeoutMs: Long = 30_000) : AutoCloseable {
    enum class Status { RUNNING, DONE, FAILED, CANCELLED }

    /** [text] is the answer when DONE, the reason when FAILED. */
    data class Info(
        val id: String, val parentSessionId: String, val type: String, val description: String,
        val subSessionId: String, val startedAtMs: Long, val status: Status, val text: String?,
    )

    private class Entry(val info: Info, val job: CompletableJob) {
        @Volatile var status = Status.RUNNING
        @Volatile var text: String? = null
        @Volatile var announced = false
        fun info() = info.copy(status = status, text = text)
    }

    /** Called when one starts and when it ends; what it throws is dropped. */
    @Volatile var onChange: ((Info) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = LinkedHashMap<String, Entry>()
    private val ids = AtomicInteger()
    @Volatile private var closed = false

    /**
     * Starts [run] with [context] (the parent's, minus its Job) plus a [DenialLimit] whose 3rd denial stops it.
     * Throws IllegalStateException when [maxRunning] already run or after [close].
     */
    fun start(
        parentSessionId: String, type: String, description: String, subSessionId: String,
        context: CoroutineContext, run: suspend (DenialLimit) -> String,
    ): Info {
        val entry = synchronized(this) {
            check(!closed) { "background subagents are closed" }
            val running = entries.values.filter { it.status == Status.RUNNING }
            check(running.size < maxRunning) {
                "$maxRunning subagents are already running: " + running.joinToString("; ") { "${it.info.id} (${it.info.type})" } +
                    ". Wait for one with subagent_result(id) or stop it with subagent_cancel(id) first."
            }
            val id = "b${ids.incrementAndGet()}"
            Entry(Info(id, parentSessionId, type, description, subSessionId, System.currentTimeMillis(), Status.RUNNING, null),
                Job(scope.coroutineContext.job)).also { entries[id] = it }
        }
        notify(entry.info)
        val limit = DenialLimit(entry.job)
        // ATOMIC: [run] starts even when cancelled first, so its finally (saving the session) always runs.
        scope.launch(context + entry.job + limit, start = CoroutineStart.ATOMIC) {
            val (status, text) = try {
                Status.DONE to run(limit)
            } catch (_: CancellationException) {
                if (limit.tripped) Status.FAILED to limit.reason() else Status.CANCELLED to null
            } catch (ex: Throwable) { // an Error too: else it stays RUNNING and holds a slot for good
                Status.FAILED to (ex.message ?: ex.toString())
            }
            end(entry, status, text)
        }
        entry.job.complete() // ends with its one child
        return entry.info
    }

    private fun end(e: Entry, status: Status, text: String?) {
        synchronized(e) {
            if (e.status == Status.RUNNING) { e.status = status; e.text = text }
        }
        notify(e.info())
    }

    private fun notify(info: Info) { runCatching { onChange?.invoke(info) } }

    private fun find(sessionId: String?, id: String): Entry? =
        synchronized(this) { entries[id]?.takeIf { sessionId == null || it.info.parentSessionId == sessionId } }

    /** Its state, waiting up to [waitSeconds] (max 120) for it to end. A finished result the model read counts as announced. */
    suspend fun result(sessionId: String?, id: String, waitSeconds: Int = 0): Info? {
        val e = find(sessionId, id) ?: return null
        withTimeoutOrNull(waitSeconds.coerceIn(0, 120) * 1000L) { e.job.join() }
        if (sessionId != null && e.status != Status.RUNNING) e.announced = true
        return e.info()
    }

    /** Stops it (its session is still saved); an ended one just returns its final state. */
    fun cancel(sessionId: String?, id: String): Info? {
        val e = find(sessionId, id) ?: return null
        synchronized(e) {
            if (e.status == Status.RUNNING) { e.status = Status.CANCELLED; e.job.cancel() }
        }
        if (sessionId != null) e.announced = true
        return e.info()
    }

    fun list(sessionId: String? = null): List<Info> = synchronized(this) {
        entries.values.filter { sessionId == null || it.info.parentSessionId == sessionId }.map { it.info() }
    }

    private val claimed = HashSet<String>()

    /** Marks [subSessionId] as being run by a resume; false when one already runs it. [release] when it ends. */
    fun claim(subSessionId: String): Boolean = synchronized(claimed) { claimed.add(subSessionId) }

    fun release(subSessionId: String) { synchronized(claimed) { claimed.remove(subSessionId) } }

    /** The run still working on [subSessionId], if any (a cancelled one counts until its session is saved). */
    fun runningOn(subSessionId: String): Info? =
        synchronized(this) { entries.values.firstOrNull { it.info.subSessionId == subSessionId && !it.job.isCompleted }?.info() }

    /** Ended runs of [sessionId] not yet announced to it; marks them announced. */
    fun takeFinished(sessionId: String): List<Info> = synchronized(this) {
        entries.values.filter { it.info.parentSessionId == sessionId && it.status != Status.RUNNING && !it.announced }
            .onEach { it.announced = true }.map { it.info() }
    }

    /** Announces [taken] again next time: the turn they went into failed before the model could read them. */
    fun putBack(taken: List<Info>) = synchronized(this) { taken.forEach { entries[it.id]?.announced = false } }

    override fun close() {
        val all = synchronized(this) { closed = true; entries.values.toList() }
        all.forEach { e -> synchronized(e) { if (e.status == Status.RUNNING) { e.status = Status.CANCELLED; e.job.cancel() } } }
        val jobs = all.map { it.job }
        // Lets each run's finally save its session (and a worktree run commit its branch) before the process exits.
        runBlocking { withTimeoutOrNull(closeTimeoutMs) { jobs.joinAll() } }
        scope.cancel()
    }
}
