package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.sophi.context.ContextEngine
import dev.sophi.context.RegisterResult
import dev.sophi.store.arcade.ArcadeHost
import dev.sophi.store.arcade.IncompatibleHostException
import dev.sophi.store.arcade.Role
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Path

internal const val NOT_READY_MSG = "context index not ready (another Sophi may be starting, or it's from a different version) — try again"

/** Session start: index the project the user is working in, with consent rules (spec §3.3). */
fun registerCwd(engine: ContextEngine, cwd: Path, echo: (String) -> Unit) {
    when (val r = engine.register(cwd, auto = true)) {
        is RegisterResult.Refused -> echo("context: ${r.reason}")
        is RegisterResult.AlreadyCovered -> {   // resume an interrupted first scan at session start
            val last = r.project.lastScanAt
            if (last == null || System.currentTimeMillis() - last > 30_000) engine.requestScan(r.project.id)
        }
        else -> Unit   // Registered already requested its scan
    }
}

class ContextAdd(private val engine: ContextEngine, private val dir: Path, private val echo: (String) -> Unit) {
    fun run() = when (val r = engine.register(dir, auto = false)) {
        is RegisterResult.Registered -> echo("Added ${r.project.root} — it's indexed in the background while a Sophi session runs, and searched from sessions started inside it" + if (r.replaced.isNotEmpty()) " — replaces ${r.replaced.size} nested project(s)" else "")
        is RegisterResult.AlreadyCovered -> echo("Already covered by ${r.project.root}")
        is RegisterResult.Refused -> echo("Not indexed: ${r.reason}")
    }
}

class ContextList(private val engine: ContextEngine, private val echo: (String) -> Unit) {
    fun run() {
        val ps = engine.projectsOrNull() ?: run { echo(NOT_READY_MSG); return }
        if (ps.isEmpty()) { echo("No indexed projects."); return }
        ps.forEach { p -> echo("${p.id}  ${p.status}${if (p.partial) " (partial)" else ""}  files=${p.fileCount ?: "…"}  ${p.root}") }
    }
}

class ContextRemove(private val engine: ContextEngine, private val idOrDir: String, private val echo: (String) -> Unit) {
    fun run() {
        val ps = engine.projectsOrNull() ?: run { echo(NOT_READY_MSG); return }
        val p = ps.firstOrNull { it.id == idOrDir }
            ?: runCatching { Path.of(idOrDir).toRealPath().toString() }.getOrNull()?.let { root -> ps.firstOrNull { it.root == root } }
        if (p == null) { echo("No indexed project matches $idOrDir"); return }
        engine.remove(p.id); echo("Removed ${p.root}")
    }
}

/** Deletes the context database — only when no Sophi process is hosting it. Leaves secret/host.json/the lock alone. */
class ContextReset(private val root: Path, private val echo: (String) -> Unit) {
    fun run() {
        val probe = try {
            ArcadeHost.open(root, ContextEngine.DATABASE, ContextEngine.SCHEMA_VERSION, "reset", waitForHostMillis = 500)
        } catch (e: IncompatibleHostException) {
            echo("the index is used by a different Sophi version — quit it first"); return
        } catch (e: Exception) { null }
        val isHost = try { probe?.role == Role.HOST } finally { probe?.close() }
        // A failed open is either a corrupted database (nobody hosting) or a live host we couldn't attach to:
        // only the lock tells them apart.
        if (!isHost && (probe != null || lockHeld())) { echo("Another Sophi process is using the index — quit it first."); return }
        val db = root.resolve("databases").resolve(ContextEngine.DATABASE)
        // outside databases/: the server would try to load a moved-aside corrupt copy
        if (Files.exists(db)) Files.move(db, root.resolve("${ContextEngine.DATABASE}.corrupt-${System.currentTimeMillis()}"))
        echo("Context index moved aside (${ContextEngine.DATABASE}.corrupt-* under the db folder); it will be rebuilt and projects re-indexed on next use.")
    }

    private fun lockHeld(): Boolean {
        Files.createDirectories(root)
        return try {
            FileChannel.open(root.resolve("host.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
                val l = ch.tryLock(); l?.release(); l == null
            }
        } catch (e: OverlappingFileLockException) { true }
    }
}

/** Runs [block] with a ready engine, or prints the not-ready message and does nothing. */
private fun withEngine(echo: (String) -> Unit, block: (ContextEngine) -> Unit) =
    ContextEngine().start().use { if (it.awaitReady(5_000)) block(it) else echo(NOT_READY_MSG) }

class ContextCommand : CliktCommand(name = "context", help = "Manage the projects Sophi indexes for search_context") { override fun run() = Unit }

class ContextAddCommand : CliktCommand(name = "add", help = "Index a folder") {
    private val dir by argument()
    override fun run() = withEngine(::echo) { ContextAdd(it, Path.of(dir)) { m -> echo(m) }.run() }
}

class ContextListCommand : CliktCommand(name = "list", help = "List indexed projects") {
    override fun run() = withEngine(::echo) { ContextList(it) { m -> echo(m) }.run() }
}

class ContextRemoveCommand : CliktCommand(name = "remove", help = "Stop indexing a project (by id or folder)") {
    private val target by argument()
    override fun run() = withEngine(::echo) { ContextRemove(it, target) { m -> echo(m) }.run() }
}

class ContextResetCommand : CliktCommand(name = "reset", help = "Delete the context index (it is rebuilt from your files)") {
    override fun run() = ContextReset(ContextEngine.defaultRoot()) { m -> echo(m) }.run()
}

class ContextEvalCommand : CliktCommand(name = "eval", help = "Measure retrieval quality on a labelled query set") {
    private val projectSpecs by option("--project", help = "NAME=PATH, repeatable").multiple(required = true)
    private val queries by option("--queries").required()
    private val hintGrid by option("--hint-grid", help = "Sweep the hint gate (minScore x minRatio) instead of scoring retrieval").flag()
    override fun run() {
        val projects = projectSpecs.associate { it.substringBefore('=') to Path.of(it.substringAfter('=')) }
        val eval = dev.sophi.context.ContextEval(projects)
        val qs = dev.sophi.context.ContextEval.load(Path.of(queries))
        if (!hintGrid) { echo(eval.run(qs).toString()); return }
        val (grid, tops) = eval.hintAnalysis(qs, listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 15.0), listOf(1.0, 1.1, 1.25, 1.5, 2.0))
        val t = tops.sorted()
        echo("n=${qs.size}  top-1 score min=%.2f median=%.2f p90=%.2f max=%.2f".format(t.first(), t[t.size / 2], t[(t.size * 0.9).toInt().coerceAtMost(t.size - 1)], t.last()))
        echo("minScore minRatio coverage precision")
        grid.forEach { echo("%8.1f %8.2f %8.3f %9.3f".format(it.minScore, it.minRatio, it.coverage, it.precision)) }
    }
}
