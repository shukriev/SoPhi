package dev.sophi.core.undo

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Snapshots the workspace into a shadow git repo (outside the project) so file changes can be undone.
 * Every git call pins `--git-dir`/`--work-tree`, so the user's own `.git` is never touched.
 */
class CheckpointStore(
    root: Path,
    checkpointsHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "checkpoints"),
    private val maxFileBytes: Long = 10_000_000,
    private val git: String = "git",
    private val timeoutSeconds: Long = 60,
    private val onWarning: (String) -> Unit = { System.err.println(it) },
) {
    val root: Path = root.toAbsolutePath().normalize()
    private val shadow: Path = checkpointsHome.resolve(
        MessageDigest.getInstance("SHA-256").digest(this.root.toString().toByteArray())
            .take(8).joinToString("") { "%02x".format(it) },
    )
    private val warned = mutableSetOf<String>()

    private class Result(val ok: Boolean, val out: String)

    private fun warn(msg: String) {
        if (warned.add(msg)) onWarning(msg)
    }

    private fun run(vararg args: String): Result = exec(true, *args)

    private fun exec(warnOnFail: Boolean, vararg args: String): Result = try {
        val log = Files.createTempFile("sophi-git", ".out")
        try {
            // user's global config must not leak in: signing would prompt, hooksPath would run their hooks
            val p = ProcessBuilder(
                git, "--git-dir=$shadow", "--work-tree=$root", "-c", "user.name=sophi", "-c", "user.email=sophi@localhost",
                "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", *args,
            ).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            val done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!done) {
                p.descendants().forEach { it.destroyForcibly() }
                p.destroyForcibly()
            }
            val out = log.readText()
            val ok = done && p.exitValue() == 0
            if (!ok && warnOnFail) warn("checkpoint: git ${args.first()} ${if (done) "failed: ${out.trim()}" else "timed out after ${timeoutSeconds}s"}")
            Result(ok, out)
        } finally {
            log.deleteIfExists()
        }
    } catch (e: IOException) {
        warn("checkpoint: cannot run git: ${e.message}")
        Result(false, "")
    }

    /** Commit sha of the workspace as it is now, or null when git is unusable. Reuses HEAD when nothing changed. */
    @Synchronized
    fun snapshot(): String? {
        if (!shadow.resolve("HEAD").exists()) {
            shadow.createDirectories()
            if (!run("init", "-q").ok) return null
        }
        val status = run("status", "--porcelain", "-z", "--untracked-files=all")
        if (!status.ok) return null
        // entries are "XY path"; a rename entry is followed by its source path, which fails the size check harmlessly
        val changed = status.out.split('\u0000').filter { it.length > 3 }.map { it.substring(3) }
        var left = changed.size
        for (path in changed) {
            val f = root.resolve(path)
            if (f.isRegularFile() && f.fileSize() > maxFileBytes) {
                warn("checkpoint: skipping $path (over $maxFileBytes bytes)")
                shadow.resolve("info").createDirectories()
                shadow.resolve("info/exclude").appendText("/" + path.replace(Regex("""([\\*?\[!#\s])"""), "\\\\$1") + "\n")
                left--
            }
        }
        val hasHead = exec(false, "rev-parse", "-q", "--verify", "HEAD").ok
        if (left > 0 || !hasHead) {
            if (!run("add", "-A").ok) return null
            if (!run("commit", "-q", "--allow-empty", "-m", "checkpoint").ok) return null
        }
        return run("rev-parse", "HEAD").takeIf { it.ok }?.out?.trim()
    }

    /** `git diff --stat <from> <current snapshot>`; "" when nothing differs. */
    fun diffStat(from: String): String {
        val now = snapshot() ?: return ""
        return run("diff", "--stat", from, now).takeIf { it.ok }?.out?.trim().orEmpty()
    }

    /** Snapshots the current state first (so a restore can itself be undone), then makes the workspace match [to]. Throws when git fails. */
    @Synchronized
    fun restore(to: String) {
        checkNotNull(snapshot()) { "checkpoint: cannot snapshot before restore" }
        val r = run("restore", "--source=$to", "--staged", "--worktree", "--", ".")
        check(r.ok) { "checkpoint restore failed: ${r.out.trim()}" }
    }
}
