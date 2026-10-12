package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.sophi.context.ContextEngine
import dev.sophi.context.RegisterResult
import dev.sophi.store.arcade.ArcadeHost
import dev.sophi.store.arcade.IncompatibleHostException
import dev.sophi.store.arcade.Role
import java.nio.file.Files
import java.nio.file.Path

internal const val NOT_READY_MSG = "context index not ready (another Sophi may be starting, or it's from a different version) — try again"

/** Session start: index the project the user is working in, with consent rules (spec §3.3). */
fun registerCwd(engine: ContextEngine, cwd: Path, echo: (String) -> Unit) {
    when (val r = engine.register(cwd, auto = true)) {
        is RegisterResult.Refused -> echo("context: ${r.reason}")
        else -> Unit
    }
}

class ContextAdd(private val engine: ContextEngine, private val dir: Path, private val echo: (String) -> Unit) {
    fun run() = when (val r = engine.register(dir, auto = false)) {
        is RegisterResult.Registered -> echo("Indexing ${r.project.root} (${r.project.id})" + if (r.replaced.isNotEmpty()) " — replaces ${r.replaced.size} nested project(s)" else "")
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
        if (!isHost) { echo("Another Sophi process is using the index — quit it first."); return }
        val db = root.resolve("databases").resolve(ContextEngine.DATABASE)
        if (Files.exists(db)) db.toFile().deleteRecursively()
        echo("Context index deleted; projects will be re-added and re-indexed on next use.")
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
    override fun run() {
        val projects = projectSpecs.associate { it.substringBefore('=') to Path.of(it.substringAfter('=')) }
        echo(dev.sophi.context.ContextEval(projects).run(dev.sophi.context.ContextEval.load(Path.of(queries))).toString())
    }
}
