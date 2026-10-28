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
 * else of the host is shared. Removed on [close], by a shutdown hook, or (after a `kill -9`) by the
 * next sandbox to start, which removes containers whose `sophi.pid` process is gone.
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
    private val hook = Thread { remove() }

    fun runArgs(): List<String> {
        val owner = runCatching { "${Files.getAttribute(root, "unix:uid")}:${Files.getAttribute(root, "unix:gid")}" }.getOrNull()
        return listOf("docker", "run", "-d", "--rm", "--name", name, "--label", "sophi.pid=${ProcessHandle.current().pid()}",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--cpus", "2", "--memory", "2g", "--pids-limit", "512") +
            (if (network) emptyList() else listOf("--network", "none")) +
            (owner?.let { listOf("--user", it) } ?: emptyList()) +
            listOf("-e", "HOME=/tmp", "-v", "$root:$root", "-w", "$root", image, "sleep", "infinity")
    }

    /** Starts the container once (the first run pulls the image); throws with Docker's message when it can't. */
    @Synchronized
    fun start() {
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

    @Synchronized
    override fun close() {
        if (!started) return
        started = false
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        remove()
    }

    private fun remove() { docker(listOf("rm", "-f", name)) }

    private fun removeOrphans() {
        val (code, out) = docker(listOf("ps", "-a", "--filter", "label=sophi.pid", "--format", "{{.ID}} {{.Label \"sophi.pid\"}}"))
        if (code != 0) return
        out.lines().mapNotNull { line -> line.split(" ").takeIf { it.size == 2 } }
            .filter { (_, pid) -> pid.toLongOrNull()?.let { ProcessHandle.of(it).isEmpty } ?: false }
            .forEach { (id, _) -> docker(listOf("rm", "-f", id)) }
    }

    companion object {
        /** The Docker daemon answers. */
        val available: Boolean by lazy { docker(listOf("version", "--format", "{{.Server.Version}}"), timeoutSeconds = 10).first == 0 }
    }
}
