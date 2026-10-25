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
import kotlin.io.path.writeText

/** A gitignore line matching exactly [path] (relative to the work tree); a trailing `/` stays a directory match. */
private fun anchored(path: String) = "/" + path.replace(Regex("""([\\*?\[!#\s])"""), "\\\\$1") + "\n"

private fun nulSplit(out: String) = out.split('\u0000').filter { it.isNotEmpty() }

/**
 * Snapshots the workspace into a shadow git repo (outside the project) so file changes can be undone.
 * Every git call that writes pins `--git-dir`/`--work-tree`, so the user's own `.git` is never touched.
 */
class CheckpointStore(
    root: Path,
    checkpointsHome: Path = Path.of(System.getProperty("user.home"), ".sophi", "checkpoints"),
    private val maxFileBytes: Long = 10_000_000,
    private val git: String = "git",
    private val timeoutSeconds: Long = 60,
    home: Path = Path.of(System.getProperty("user.home")),
    sophiHome: Path = home.resolve(".sophi"),
    private val onWarning: (String) -> Unit = { System.err.println(it) },
) {
    val root: Path = root.toAbsolutePath().normalize()
    private val shadow: Path = checkpointsHome.toAbsolutePath().normalize().resolve(
        MessageDigest.getInstance("SHA-256").digest(this.root.toString().toByteArray())
            .take(8).joinToString("") { "%02x".format(it) },
    )
    /** The ignored files of a git repo enclosing [root], which the shadow repo can't see itself. */
    private val repoIgnores: Path = shadow.resolve("sophi-repo-ignores")
    // A shadow repo inside its own work tree would snapshot itself, and a restore would delete whatever it never saw.
    private val refusal: String? = when {
        this.root.parent == null || this.root == home.toAbsolutePath().normalize() ->
            "checkpoint: off for ${this.root}, too broad to snapshot; start SoPhi in a project folder"
        shadow.startsWith(this.root) || sophiHome.toAbsolutePath().normalize().startsWith(this.root) ->
            "checkpoint: off for ${this.root}, it contains SoPhi's own data; start SoPhi in a project folder"
        else -> null
    }
    private val warned = mutableSetOf<String>()
    /** Nested repos with no commit yet, as of the latest snapshot: `add -A` fails on them, so they are left out. */
    @Volatile private var unbornRepos = emptyList<String>()

    private class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    private fun warn(msg: String) {
        if (warned.add(msg)) onWarning(msg)
    }

    private fun run(vararg args: String): Result = exec(true, *args)

    private fun exec(warnOnFail: Boolean, vararg args: String, input: String? = null, userRepo: Boolean = false): Result = try {
        val log = Files.createTempFile("sophi-git", ".out")
        val stdin = input?.let { Files.createTempFile("sophi-git", ".in").apply { writeText(it) } }
        try {
            // Config that runs a program: a repo SoPhi doesn't own (or the user's global config) would otherwise run it on
            // every snapshot, unconfirmed: core.fsmonitor on any index read, hooks on any hook-running command.
            val noExec = arrayOf("-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null")
            // user's global config must not leak in: signing would prompt
            val cmd = if (userRepo) listOf(git, "--no-optional-locks", *noExec, *args) else listOf(
                git, "--git-dir=$shadow", "--work-tree=$root", "-c", "user.name=sophi", "-c", "user.email=sophi@localhost",
                "-c", "commit.gpgsign=false", *noExec,
                *(if (repoIgnores.exists()) arrayOf("-c", "core.excludesFile=$repoIgnores") else emptyArray()), *args,
            )
            val pb = ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
            stdin?.let { pb.redirectInput(it.toFile()) }
            val p = pb.start()
            val done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!done) {
                p.descendants().forEach { it.destroyForcibly() }
                p.destroyForcibly()
            }
            val out = log.readText()
            val code = if (done) p.exitValue() else -1
            if (code != 0 && warnOnFail) warn("checkpoint: git ${args.first()} ${if (done) "failed: ${out.trim()}" else "timed out after ${timeoutSeconds}s"}")
            Result(code, out)
        } finally {
            log.deleteIfExists()
            stdin?.deleteIfExists()
        }
    } catch (e: IOException) {
        warn("checkpoint: cannot run git: ${e.message}")
        Result(-1, "")
    }

    /**
     * Inside a git repo, its .gitignore files above [root], `.git/info/exclude` and the global excludes would be missed:
     * a read-only `ls-files` in the user's repo lists what they ignore, for the shadow's `core.excludesFile`.
     */
    private fun syncRepoIgnores() {
        val r = exec(false, "ls-files", "-z", "--others", "--ignored", "--exclude-standard", "--directory", userRepo = true)
        if (r.ok) repoIgnores.writeText(nulSplit(r.out).joinToString("") { anchored(it) }) else repoIgnores.deleteIfExists()
    }

    /** Commit sha of the workspace as it is now, or null when git is unusable. Reuses HEAD when nothing changed. */
    @Synchronized
    fun snapshot(): String? {
        refusal?.let { warn(it); return null }
        if (!shadow.resolve("HEAD").exists()) {
            shadow.createDirectories()
            if (!run("init", "-q").ok) return null
        }
        syncRepoIgnores()
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
                shadow.resolve("info/exclude").appendText(anchored(path))
                left--
            }
        }
        // a nested repo shows as one untracked "dir/" entry; read-only rev-parse inside it tells whether it has a commit
        unbornRepos = changed.filter { it.endsWith("/") && root.resolve(it).resolve(".git").exists() &&
            !exec(false, "-C", it, "rev-parse", "-q", "--verify", "HEAD", userRepo = true).ok }
        for (repo in unbornRepos) warn("checkpoint: leaving out $repo, a nested git repo with no commit yet; changes inside it can't be undone")
        left -= unbornRepos.size
        val hasHead = exec(false, "rev-parse", "-q", "--verify", "HEAD").ok
        if (left > 0 || !hasHead) {
            if (!run("add", "-A", "--", ".", *unbornRepos.map { ":(exclude,literal)$it" }.toTypedArray()).ok) return null
            if (!run("commit", "-q", "--allow-empty", "-m", "checkpoint").ok) return null
        }
        return run("rev-parse", "HEAD").takeIf { it.ok }?.out?.trim()
    }

    /** What [restore] to [to] would change, as `git diff --stat --summary <now> <to>`; "" when nothing differs. */
    fun diffStat(to: String): String {
        val now = snapshot() ?: return ""
        return run("diff", "--stat", "--summary", now, to).takeIf { it.ok }?.out?.trim().orEmpty()
    }

    /**
     * Nested repos in [checkpoint] or the latest snapshot: `add -A` keeps only their commit, or nothing when they have none,
     * so edits inside them can't be undone.
     */
    fun nestedRepos(checkpoint: String): List<String> = (listOf(checkpoint, "HEAD")
        .flatMap { sha -> exec(false, "ls-tree", "-r", "-z", sha).takeIf { it.ok }?.out?.let(::nulSplit).orEmpty() }
        .filter { it.startsWith("160000 ") }.map { it.substringAfter('\t') } + unbornRepos.map { it.removeSuffix("/") }).distinct()

    private fun checked(vararg args: String, input: String? = null, okCodes: Set<Int> = setOf(0)): String {
        val r = exec(false, *args, input = input)
        check(r.code in okCodes) { "checkpoint restore failed: ${r.out.trim()}" }
        return r.out
    }

    private fun restorePaths(to: String, pathspecs: List<String>) {
        val file = Files.createTempFile("sophi-git", ".pathspec")
        try {
            file.writeText(pathspecs.joinToString("\u0000"))
            checked("restore", "--source=$to", "--staged", "--worktree", "--pathspec-from-file=$file", "--pathspec-file-nul")
        } finally {
            file.deleteIfExists()
        }
    }

    /** Of [paths], those ignored by the ignore files now in the work tree, tracked or not. */
    private fun ignored(paths: List<String>): List<String> =
        if (paths.isEmpty()) emptyList()
        else nulSplit(checked("check-ignore", "--no-index", "-z", "--stdin", input = paths.joinToString("\u0000"), okCodes = setOf(0, 1)))

    /**
     * Snapshots the current state first, then makes the workspace match [to], and returns that pre-restore snapshot so the
     * restore can itself be undone. Leaves [keep] alone, and never deletes a file ignored by the current rules or by [to]'s:
     * `add -A` followed whichever rules held when it ran. Throws when git fails.
     */
    @Synchronized
    fun restore(to: String, keep: Collection<Path> = emptyList()): String {
        val before = checkNotNull(snapshot()) { "checkpoint: cannot snapshot before restore" }
        val created = nulSplit(checked("diff", "--name-only", "-z", "--no-renames", "--diff-filter=A", to, before))
        val ignoredNow = ignored(created)
        val gitignores = nulSplit(checked("ls-tree", "-r", "--name-only", "-z", to)).filter { it == ".gitignore" || it.endsWith("/.gitignore") }
        if (gitignores.isNotEmpty()) restorePaths(to, gitignores.map { ":(literal)$it" })
        val spared = ignoredNow + ignored(created) + keep.map { it.toAbsolutePath().normalize() }
            .filter { it.startsWith(root) && it != root }.map { root.relativize(it).toString() }
        restorePaths(to, listOf(".") + spared.distinct().map { ":(exclude,literal)$it" })
        return before
    }
}
