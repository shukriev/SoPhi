package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.sophi.context.ContextEngine
import dev.sophi.context.RegisterResult
import dev.sophi.store.arcade.ArcadeHost
import dev.sophi.store.arcade.Role
import java.nio.file.Files
import java.nio.file.Path

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
        val ps = engine.projects()
        if (ps.isEmpty()) { echo("No indexed projects."); return }
        ps.forEach { p -> echo("${p.id}  ${p.status}${if (p.partial) " (partial)" else ""}  files=${p.fileCount ?: "…"}  ${p.root}") }
    }
}

class ContextRemove(private val engine: ContextEngine, private val idOrDir: String, private val echo: (String) -> Unit) {
    fun run() {
        val p = engine.projects().firstOrNull { it.id == idOrDir }
            ?: runCatching { Path.of(idOrDir).toRealPath().toString() }.getOrNull()?.let { root -> engine.projects().firstOrNull { it.root == root } }
        if (p == null) { echo("No indexed project matches $idOrDir"); return }
        engine.remove(p.id); echo("Removed ${p.root}")
    }
}

/** Deletes the context database — only when no Sophi process is hosting it. Leaves secret/host.json/the lock alone. */
class ContextReset(private val root: Path, private val echo: (String) -> Unit) {
    fun run() {
        val probe = runCatching { ArcadeHost.open(root, ContextEngine.DATABASE, ContextEngine.SCHEMA_VERSION, "reset", waitForHostMillis = 500) }.getOrNull()
        val isHost = try { probe?.role == Role.HOST } finally { probe?.close() }
        if (!isHost) { echo("Another Sophi process is using the index — quit it first."); return }
        val db = root.resolve("databases").resolve(ContextEngine.DATABASE)
        if (Files.exists(db)) db.toFile().deleteRecursively()
        echo("Context index deleted; projects will be re-added and re-indexed on next use.")
    }
}

private fun cliEngine() = ContextEngine().start().also { it.awaitReady(5_000) }

class ContextCommand : CliktCommand(name = "context", help = "Manage the projects Sophi indexes for search_context") { override fun run() = Unit }

class ContextAddCommand : CliktCommand(name = "add", help = "Index a folder") {
    private val dir by argument()
    override fun run() = cliEngine().use { ContextAdd(it, Path.of(dir)) { m -> echo(m) }.run() }
}

class ContextListCommand : CliktCommand(name = "list", help = "List indexed projects") {
    override fun run() = cliEngine().use { ContextList(it) { m -> echo(m) }.run() }
}

class ContextRemoveCommand : CliktCommand(name = "remove", help = "Stop indexing a project (by id or folder)") {
    private val target by argument()
    override fun run() = cliEngine().use { ContextRemove(it, target) { m -> echo(m) }.run() }
}

class ContextResetCommand : CliktCommand(name = "reset", help = "Delete the context index (it is rebuilt from your files)") {
    override fun run() = ContextReset(ContextEngine.defaultRoot()) { m -> echo(m) }.run()
}
