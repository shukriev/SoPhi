package dev.sophi.core.undo

import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/**
 * Snapshots the workspace into a shadow git repo (outside the project) so file changes can be undone.
 * Every git call pins `--git-dir`/`--work-tree`, so the user's own `.git` is never touched.
 */
class CheckpointStore(
    val root: Path,
    checkpointsHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "checkpoints"),
    private val maxFileBytes: Long = 10_000_000,
    private val git: String = "git",
    private val onWarning: (String) -> Unit = { System.err.println(it) },
) {
    private val shadow: Path = checkpointsHome.resolve(
        MessageDigest.getInstance("SHA-256").digest(root.toAbsolutePath().normalize().toString().toByteArray())
            .take(8).joinToString("") { "%02x".format(it) },
    )
    private val warned = mutableSetOf<String>()

    private class Result(val ok: Boolean, val out: String)

    private fun warn(msg: String) {
        if (warned.add(msg)) onWarning(msg)
    }

    private fun run(vararg args: String): Result = try {
        val p = ProcessBuilder(
            git, "--git-dir=$shadow", "--work-tree=$root", "-c", "user.name=sophi", "-c", "user.email=sophi@localhost", *args,
        ).directory(root.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor() == 0
        if (!ok) warn("checkpoint: git ${args.first()} failed: ${out.trim()}")
        Result(ok, out)
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
        val hasHead = run("rev-parse", "-q", "--verify", "HEAD").ok
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

    /** Snapshots the current state first (so a restore can itself be undone), then makes the workspace match [to]. */
    @Synchronized
    fun restore(to: String) {
        snapshot() ?: return
        run("restore", "--source=$to", "--staged", "--worktree", "--", ".")
    }
}
