package dev.sophi.core.agent

import dev.sophi.core.tools.Tool
import dev.sophi.core.undo.GIT_NO_EXEC
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.random.Random

/**
 * Git worktrees a subagent works in, so its file changes land on a branch instead of in [root]'s tree. Each one is
 * off HEAD of the repo holding [root], under [home] (outside the project, so checkpoints and grep never see it).
 * [toolsAt] builds the file/shell tools rooted at a worktree folder. Every git call pins [GIT_NO_EXEC].
 */
class SubagentWorktrees(
    private val root: Path,
    private val home: Path = Path.of(System.getProperty("user.home"), ".sophi", "worktrees"),
    private val git: String = "git",
    val toolsAt: (Path) -> List<Tool>,
) {
    private class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    private fun git(dir: Path, vararg args: String): Result = try {
        val log = Files.createTempFile("sophi-git", ".out")
        try {
            val p = ProcessBuilder(listOf(git, "--no-optional-locks", *GIT_NO_EXEC.toTypedArray(), *args))
                .directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            val done = p.waitFor(120, TimeUnit.SECONDS)
            if (!done) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
            Result(if (done) p.exitValue() else -1, log.readText().trim())
        } finally {
            log.deleteIfExists()
        }
    } catch (e: IOException) {
        Result(-1, "cannot run git: ${e.message}")
    }

    /** A new worktree for a subagent of type [type]; throws [IllegalStateException] saying why when there can't be one. */
    fun open(type: String): Worktree {
        val real = runCatching { root.toAbsolutePath().normalize().toRealPath() }.getOrElse { error("no folder at $root") }
        val top = git(real, "rev-parse", "--show-toplevel")
        check(top.ok) { "not in a git repository (${top.out.lineSequence().firstOrNull().orEmpty()})" }
        val repo = Path.of(top.out).toRealPath()
        val head = git(repo, "rev-parse", "-q", "--verify", "HEAD")
        check(head.ok) { "the repository has no commit yet" }
        val dirty = git(repo, "status", "--porcelain").let { !it.ok || it.out.isNotEmpty() }
        val slug = type.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifEmpty { "subagent" }.take(40)
        val name = "$slug-${"%06x".format(Random.nextInt(0x1000000))}"
        val hash = MessageDigest.getInstance("SHA-256").digest(repo.toString().toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        val path = home.toAbsolutePath().normalize().resolve(hash).resolve(name)
        val branch = "sophi-subagent/$name"
        path.parent.createDirectories()
        val add = git(repo, "worktree", "add", "-q", "-b", branch, path.toString(), head.out)
        check(add.ok) { "git worktree add failed: ${add.out}" }
        return Worktree(repo, path.toRealPath(), branch, head.out, dirty, real)
    }

    inner class Worktree internal constructor(
        private val repo: Path,
        val path: Path,
        val branch: String,
        val base: String,
        /** Whether the main tree had uncommitted changes, which the worktree (off HEAD) doesn't have. */
        val dirty: Boolean,
        mainRoot: Path,
    ) {
        /** The session root's counterpart inside the worktree. */
        val root: Path = path.resolve(repo.relativize(mainRoot).toString()).normalize()

        /**
         * Commits whatever the subagent left as [message], then removes the worktree and branch when nothing changed.
         * A worktree with changes is kept with its branch. Returns one line for the model; never throws.
         */
        fun finish(message: String): String {
            val short = base.take(7)
            fun kept(why: String) = "The worktree at $path (branch $branch) was kept: $why. Its changes there are not committed."
            val add = git(path, "add", "-A")
            if (!add.ok) return kept("git add failed: ${add.out}")
            if (git(path, "diff", "--cached", "--quiet").code != 0) {
                val subject = message.lineSequence().first().take(72)
                val commit = git(path, "-c", "user.name=sophi", "-c", "user.email=sophi@localhost", "-c", "commit.gpgsign=false",
                    "commit", "-q", "-m", subject)
                if (!commit.ok) return kept("git commit failed: ${commit.out}")
            }
            val head = git(path, "rev-parse", "HEAD")
            val status = git(path, "status", "--porcelain")
            if (head.ok && head.out == base && status.ok && status.out.isEmpty()) {
                val remove = git(repo, "worktree", "remove", path.toString())
                if (!remove.ok) return "No file changes, but the worktree at $path could not be removed: ${remove.out}"
                git(repo, "branch", "-D", branch)
                return "No file changes; the worktree was removed."
            }
            val note = if (dirty) " It started from $short (HEAD), without your uncommitted changes." else ""
            val stat = git(repo, "diff", "--stat", base, branch).out
            return "Changes are on branch $branch (worktree $path, from $short).$note\n$stat\n" +
                "Review: git diff $short $branch. Take them: git merge $branch. " +
                "Drop them: git worktree remove --force $path && git branch -D $branch"
        }
    }
}
