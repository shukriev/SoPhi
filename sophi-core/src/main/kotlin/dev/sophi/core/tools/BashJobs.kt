package dev.sophi.core.tools

import dev.sophi.core.session.SessionIdContext
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Background `bash` jobs of one runtime. Output goes to a log under [home]/<this JVM's pid>, so several Sophi
 * processes can share [home]; a process clears only the dirs of processes that have ended. [close] (and a JVM
 * shutdown hook) kill every job's process tree: no job outlives Sophi.
 */
class BashJobs(home: Path, private val maxRunning: Int = 8) : AutoCloseable {
    data class JobStart(val id: String, val initialOutput: String)
    data class JobInfo(val id: String, val sessionId: String, val command: String, val status: String, val startedAtMs: Long)

    private class Job(val id: String, val sessionId: String, val command: String, val process: Process, val log: Path, val startedAtMs: Long) {
        @Volatile var killed = false
        var offset = 0L
        val status: String get() = when {
            killed -> "killed"
            process.isAlive -> "running"
            else -> "exited ${process.exitValue()}"
        }
    }

    private val dir: Path = home.resolve(ProcessHandle.current().pid().toString())
    private companion object { const val SKIPPED = "\u0000skipped\u0000" }
    private val jobs = LinkedHashMap<String, Job>()
    private val ids = AtomicInteger()
    @Volatile private var closed = false

    internal val shutdownHook = Thread(::close, "sophi-jobs-cleanup")

    init {
        runCatching {
            if (Files.isDirectory(home)) home.listDirectoryEntries().forEach { d ->
                val pid = d.name.toLongOrNull() ?: return@forEach
                if (ProcessHandle.of(pid).isEmpty) { reapOrphans(d); d.toFile().deleteRecursively() }
            }
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    /**
     * Kills the jobs a Sophi that died without closing (kill -9, a crash) left running, from their pid files.
     * A pid is only killed when its start time matches, so a reused pid is never touched.
     */
    private fun reapOrphans(dir: Path) {
        dir.toFile().walk().filter { it.name.endsWith(".pid") }.forEach { f ->
            val (pid, started) = f.readText().trim().split(" ").mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 } ?: return@forEach
            ProcessHandle.of(pid).filter { h -> h.info().startInstant().map { it.toEpochMilli() == started }.orElse(false) }
                .ifPresent { h -> h.descendants().forEach { it.destroyForcibly() }; h.destroyForcibly() }
        }
    }

    /** Starts [builder] (already sandbox-wrapped) as a job; [release] runs when it ends. Throws when [maxRunning] jobs run. */
    suspend fun start(sessionId: String, command: String, builder: ProcessBuilder, release: () -> Unit = {}): JobStart {
        val job = synchronized(this) {
            check(!closed) { "background jobs are closed" }
            val running = jobs.values.filter { it.process.isAlive && !it.killed }
            check(running.size < maxRunning) {
                "$maxRunning jobs are already running: " + running.joinToString("; ") { "${it.id} (${it.command})" } +
                    ". Stop one with bash_kill(id) first."
            }
            val id = "j${ids.incrementAndGet()}"
            val log = dir.resolve(sessionId).createDirectories().resolve("$id.log")
            val process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start()
            process.outputStream.close() // nothing is ever fed to a job; an open stdin would hang a command that reads it
            process.onExit().thenRun(release)
            process.toHandle().info().startInstant().ifPresent { log.resolveSibling("$id.pid").toFile().writeText("${process.pid()} ${it.toEpochMilli()}") }
            Job(id, sessionId, command, process, log, System.currentTimeMillis()).also { jobs[id] = it }
        }
        val deadline = System.currentTimeMillis() + 2_000
        while (job.process.isAlive && System.currentTimeMillis() < deadline) delay(100)
        return JobStart(job.id, tail(job, read(job, 16_000), 200, 16_000))
    }

    private fun find(sessionId: String?, id: String): Job? = synchronized(this) { jobs[id]?.takeIf { sessionId == null || it.sessionId == sessionId } }

    /**
     * New output since the last read, from [Job.offset] to the end. Past [maxChars]×4 bytes only the end is read
     * (from a line start), marked with [SKIPPED], so a huge log is never loaded whole.
     */
    private fun read(job: Job, maxChars: Int): String = synchronized(job) {
        if (!Files.exists(job.log)) return ""
        RandomAccessFile(job.log.toFile(), "r").use { f ->
            val end = f.length()
            if (end <= job.offset) return ""
            val from = maxOf(job.offset, end - maxChars * 4L)
            f.seek(from)
            val bytes = ByteArray((end - from).toInt())
            f.readFully(bytes)
            val skipped = from > job.offset
            job.offset = end
            val text = bytes.decodeToString()
            if (skipped) SKIPPED + text.substringAfter('\n') else text
        }
    }

    private fun tail(job: Job, read: String, maxLines: Int, maxChars: Int): String {
        val skipped = read.startsWith(SKIPPED)
        val text = read.removePrefix(SKIPPED)
        var lines = text.trimEnd('\n').lines().let { if (it == listOf("")) emptyList() else it }
        val dropped = (lines.size - maxLines).coerceAtLeast(0)
        lines = lines.takeLast(maxLines)
        var body = lines.joinToString("\n")
        if (body.length > maxChars) body = body.takeLast(maxChars)
        return when {
            skipped -> "(earlier output in ${job.log})\n$body"
            dropped > 0 -> "($dropped earlier lines in ${job.log})\n$body"
            else -> body
        }
    }

    /**
     * Status line (`running` / `exited <code>` / `killed`), then the output written since the last call, cut to a tail.
     * With [waitSeconds], returns as soon as the job exits or writes something new.
     */
    suspend fun output(sessionId: String, id: String, waitSeconds: Int = 0, maxLines: Int = 200, maxChars: Int = 16_000): String {
        val job = find(sessionId, id) ?: return "Error: no such job $id"
        val deadline = System.currentTimeMillis() + waitSeconds.coerceIn(0, 120) * 1000L
        while (job.process.isAlive && !job.killed && System.currentTimeMillis() < deadline &&
            Files.size(job.log) <= job.offset) delay(100)
        if (!job.process.isAlive) job.process.waitFor() // let the log settle before the last read
        return job.status + "\n" + tail(job, read(job, maxChars), maxLines, maxChars)
    }

    /** Kills [id]'s process tree; a null [sessionId] is the host (e.g. `/jobs kill`), which may stop any session's job. */
    fun kill(sessionId: String?, id: String): String {
        val job = find(sessionId, id) ?: return "Error: no such job $id"
        if (job.process.isAlive) { job.killed = true; killTree(job.process) }
        return "${job.status} $id\n" + tail(job, read(job, 16_000), 200, 16_000)
    }

    fun list(sessionId: String? = null): List<JobInfo> = synchronized(this) {
        jobs.values.filter { sessionId == null || it.sessionId == sessionId }.map { JobInfo(it.id, it.sessionId, it.command, it.status, it.startedAtMs) }
    }

    override fun close() {
        // Throws while the JVM is already shutting down (we're then running as that hook): nothing to remove.
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        val all = synchronized(this) { closed = true; jobs.values.toList() }
        all.filter { it.process.isAlive }.forEach { it.killed = true; killTree(it.process) }
    }

    // ponytail: a child that daemonizes (setsid, double fork) leaves the tree and survives this; catching it needs
    // a process group per job, which the JVM can't create portably.
    private fun killTree(p: Process) {
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
        p.waitFor()
    }
}

@Serializable
private data class JobArgs(val id: String, val wait_seconds: Int = 0)

private val jobJson = Json { ignoreUnknownKeys = true }

private suspend fun sessionOf() = coroutineContext[SessionIdContext]?.sessionId ?: "default"

class BashOutputTool(private val jobs: BashJobs) : Tool {
    override val name = "bash_output"
    override val description = "Read a background bash job's status and the output it wrote since you last read it (a tail). " +
        "wait_seconds (up to 120) waits for the job to exit or print something new, instead of polling with sleep."
    override val parametersJson = """
        {"type":"object","properties":{"id":{"type":"string","description":"Job id from bash(background=true), e.g. j1"},"wait_seconds":{"type":"integer","description":"Wait up to this many seconds (max 120) for new output or exit; default 0"}},"required":["id"]}
    """.trimIndent()
    override val deferred = true
    override suspend fun execute(argumentsJson: String): String {
        val args = jobJson.decodeFromString<JobArgs>(argumentsJson)
        return jobs.output(sessionOf(), args.id, args.wait_seconds)
    }
}

class BashKillTool(private val jobs: BashJobs) : Tool {
    override val name = "bash_kill"
    override val description = "Stop a background bash job (and everything it started) and return its last output."
    override val parametersJson = """
        {"type":"object","properties":{"id":{"type":"string","description":"Job id from bash(background=true), e.g. j1"}},"required":["id"]}
    """.trimIndent()
    override val deferred = true
    override suspend fun execute(argumentsJson: String): String =
        jobs.kill(sessionOf(), jobJson.decodeFromString<JobArgs>(argumentsJson).id)
}
