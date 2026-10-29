package dev.sophi.core.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exit code and combined output of `docker [args]`; -1 when it didn't finish within [timeoutSeconds]. */
private fun docker(args: List<String>, timeoutSeconds: Long = 30): Pair<Int, String> = runCatching {
    val p = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
    p.outputStream.close()
    val out = StringBuilder()
    val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
    if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching -1 to "timed out after ${timeoutSeconds}s" }
    reader.join(1000)
    p.exitValue() to out.toString().trim()
}.getOrElse { -1 to (it.message ?: it.toString()) }

/**
 * One long-lived, locked-down container per sandbox; `bash` commands run in it through `docker exec`.
 * The workspace is bind-mounted at the same path, so paths mean the same inside and out; nothing
 * else of the host is shared. Removed on [close], by a shutdown hook, or (after a `kill -9`) by
 * [removeOrphans], which the next sandbox and each CLI start run.
 *
 * ponytail: a timed-out command's `docker exec` client is killed, but the process inside runs on
 * until the container goes; `docker exec <name> pkill -f` if that bites.
 */
class DockerSandbox(
    private val root: Path,
    private val image: String = DEFAULT_DOCKER_IMAGE,
    private val network: Boolean = true,
) : AutoCloseable {
    val name = "sophi-" + UUID.randomUUID().toString().take(8)
    private var started = false
    private var closed = false
    /** Commands running in the container right now; a [close] waits for them before removing it. */
    private var running = 0
    private val hook = Thread { remove() }

    fun runArgs(): List<String> {
        val owner = runCatching { "${Files.getAttribute(root, "unix:uid")}:${Files.getAttribute(root, "unix:gid")}" }.getOrNull()
        return listOf("docker", "run", "-d", "--rm", "--name", name, "--label", "sophi.pid=${ProcessHandle.current().pid()}",
            "--label", "sophi.started=${startedMillis(ProcessHandle.current())}",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--cpus", "2", "--memory", "2g", "--pids-limit", "512") +
            (if (network) emptyList() else listOf("--network", "none")) +
            (owner?.let { listOf("--user", it) } ?: emptyList()) +
            listOf("-e", "HOME=/tmp", "-v", "$root:$root", "-w", "$root", image, "sleep", "infinity")
    }

    /** Starts the container once (the first run pulls the image); throws with Docker's message when it can't. */
    @Synchronized
    fun start() {
        check(!closed) { "this Docker sandbox is closed" }
        if (started) return
        removeOrphans()
        val (code, out) = docker(runArgs().drop(1), timeoutSeconds = 300)
        check(code == 0) { out.lines().lastOrNull { it.isNotBlank() } ?: "docker run failed" }
        started = true
        Runtime.getRuntime().addShutdownHook(hook)
    }

    fun wrap(command: List<String>): List<String> {
        start()
        return listOf("docker", "exec", "-w", root.toString(), name) + command
    }

    /** [wrap], counted as running until [release]: a switch mid-command lets it finish first. */
    @Synchronized
    fun acquire(command: List<String>): List<String> = wrap(command).also { running++ }

    @Synchronized
    fun release() {
        if (--running == 0 && closed && started) removeNow()
    }

    /** No new commands; the container goes once the running ones finish (the shutdown hook covers an exit before then). */
    @Synchronized
    override fun close() {
        closed = true
        if (started && running == 0) removeNow()
    }

    private fun removeNow() {
        started = false
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        remove()
    }

    private fun remove() { docker(listOf("rm", "-f", name)) }

    companion object {
        private fun startedMillis(p: ProcessHandle): Long? = p.info().startInstant().map { it.toEpochMilli() }.orElse(null)

        /**
         * Removes containers a Sophi left behind: its process is gone, or the pid now belongs to a
         * process that started at another time (the pid was reused).
         */
        fun removeOrphans() {
            val (code, out) = docker(listOf("ps", "-a", "--filter", "label=sophi.pid",
                "--format", "{{.ID}} {{.Label \"sophi.pid\"}} {{.Label \"sophi.started\"}}"))
            if (code != 0) return
            out.lines().map { it.split(" ") }.filter { it.size >= 2 }.filter { parts ->
                val pid = parts[1].toLongOrNull() ?: return@filter false
                val process = ProcessHandle.of(pid).orElse(null) ?: return@filter true // gone
                val started = parts.getOrNull(2)?.toLongOrNull() ?: return@filter false // older label: alive is all we know
                startedMillis(process) != started
            }.forEach { docker(listOf("rm", "-f", it[0])) }
        }

        /** The Docker daemon answers now: asked each time, so Docker started after Sophi is found. */
        val available: Boolean get() = docker(listOf("version", "--format", "{{.Server.Version}}"), timeoutSeconds = 10).first == 0
    }
}
