package dev.sophi.context.tools

import dev.sophi.context.ContextEngine
import dev.sophi.context.ProjectInfo
import dev.sophi.context.ProjectOf
import dev.sophi.context.ContextScope
import dev.sophi.core.session.SessionIdContext
import kotlinx.coroutines.currentCoroutineContext
import dev.sophi.context.ProjectMap
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path

/** Spec invariant 3: no exception escapes a context tool. */
private suspend inline fun guarded(block: () -> String): String =
    try { block() } catch (e: Exception) { "context tool failed: ${e.javaClass.simpleName}" }

private const val MAX_BYTES = 1_000_000

private val json = Json { ignoreUnknownKeys = true }

@Serializable private data class SearchArgs(val query: String = "", val limit: Int = 8)
@Serializable private data class ReadArgs(val path: String = "", val startLine: Int? = null, val endLine: Int? = null)
@Serializable private data class MapArgs(val path: String? = null)

private const val NO_PROJECT = "No indexed project for this session — run `sophi context add <dir>`, or pick a folder with the project chip in the companion."
private const val CAP = 15

@Serializable private data class SymbolArgs(val name: String = "")
@Serializable private data class RelatedArgs(val path: String = "")

/**
 * search_context / read_context / project_map / find_symbol / related (spec §6.6). [project] = the session's bound project id (null = unbound).
 * [surface] = SOPHI_CONTEXT_TOOLS: all (5) | core (search + read) | none (orientation and hint only — E3).
 */
fun contextTools(engine: ContextEngine, project: ProjectOf, surface: String = "all"): List<Tool> = when (surface) {
    "none" -> emptyList()
    "core" -> listOf(SearchContextTool(engine, project), ReadContextTool(engine, project))
    else -> listOf(SearchContextTool(engine, project), ReadContextTool(engine, project), ProjectMapTool(engine, project),
        FindSymbolTool(engine, project), RelatedTool(engine, project))
}

/** "name:path" picks a project by name; otherwise the first (primary) project. */
private fun locate(projects: List<ProjectInfo>, path: String): Pair<ProjectInfo, String>? {
    val named = path.substringBefore(':', "")
    projects.firstOrNull { it.name == named }?.let { return it to path.substringAfter(':') }
    return projects.firstOrNull()?.let { it to path }
}

private suspend fun inScope(engine: ContextEngine, project: ProjectOf): List<ProjectInfo>? {
    if (!engine.ready) return null
    val all = engine.projectsOrNull() ?: return null
    val ids = ContextScope.searchIds(project(currentCoroutineContext()[SessionIdContext]?.sessionId), all)
    return ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
}

/** First use must read as "still indexing", not "nothing here". */
private fun indexingNote(projects: List<ProjectInfo>): String? =
    projects.filter { it.lastScanAt == null }.takeIf { it.isNotEmpty() }
        ?.let { "indexing ${it.joinToString(", ") { p -> p.name }} — results incomplete; use grep/glob/read meanwhile" }

private class SearchContextTool(private val engine: ContextEngine, private val project: ProjectOf) : Tool {
    override val name = "search_context"
    override val description = "Find the files and code most relevant to a question in this project's index (ranked; faster and better than grep for 'where is X handled' questions). Returns path:lines, the symbol or heading, and a snippet."
    override val parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"What you're looking for, in words or identifiers"},"limit":{"type":"integer","description":"Max results (default 8, max 20)"}},"required":["query"]}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String = guarded { run(argumentsJson) }

    private suspend fun run(argumentsJson: String): String {
        val projects = inScope(engine, project) ?: return ContextEngine.NOT_READY
        val args = runCatching { json.decodeFromString<SearchArgs>(argumentsJson) }.getOrNull() ?: return "invalid arguments"
        if (projects.isEmpty()) return NO_PROJECT
        val hits = engine.search(args.query, projects.map { it.id }, args.limit.coerceIn(1, 20)) ?: return ContextEngine.NOT_READY
        val note = indexingNote(projects)
        if (hits.isEmpty()) return note ?: "No matches — try other words, or grep"
        val names = projects.associate { it.id to it.name }
        return (note?.plus("\n") ?: "") + hits.joinToString("\n") { h ->
            val path = if (projects.size > 1) "${names[h.projectId]}:${h.path}" else h.path
            "$path:${h.startLine}-${h.endLine} · ${h.label ?: "-"} · ${h.role.lowercase()}\n" + h.snippet.lines().joinToString("\n") { "    $it" }
        }
    }
}

private class ReadContextTool(private val engine: ContextEngine, private val project: ProjectOf) : Tool {
    override val name = "read_context"
    override val description = "Read an indexed file of this project (optionally a line range). Use after search_context."
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"Path as shown by search_context"},"startLine":{"type":"integer"},"endLine":{"type":"integer"}},"required":["path"]}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String = guarded { run(argumentsJson) }

    private suspend fun run(argumentsJson: String): String {
        val projects = inScope(engine, project) ?: return ContextEngine.NOT_READY
        val args = runCatching { json.decodeFromString<ReadArgs>(argumentsJson) }.getOrNull() ?: return "invalid arguments"
        val (project, rel) = locate(projects, args.path) ?: return "not an indexed file in this project: ${args.path}"
        val root = Path.of(project.root)
        if (rel.startsWith("/") || rel.startsWith("\\") || Path.of(rel).isAbsolute) return "not an indexed file in this project: ${args.path}"
        val file = root.resolve(rel).normalize()
        val refused = "not an indexed file in this project: ${args.path}"
        if (!file.startsWith(root)) return refused
        if (engine.fileIndexedOrNull(project.id, rel) ?: return ContextEngine.NOT_READY) { /* indexed */ } else return refused
        // Containment must survive symlinks swapped in after indexing (the rescan is async): no link at the
        // leaf, and the real path (which resolves symlinked parent dirs) must stay under the real root.
        val attrs = try { Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (e: java.io.IOException) { return refused }
        if (attrs.isSymbolicLink || !attrs.isRegularFile || attrs.size() > MAX_BYTES) return refused
        val real = try { file.toRealPath() to root.toRealPath() } catch (e: java.io.IOException) { return refused }
        if (!real.first.startsWith(real.second)) return refused
        // Reading the resolved real path with NOFOLLOW_LINKS closes the check-then-read window (a swap after the
        // checks above can't redirect the read), and the capped read bounds a file that grew past 1 MB meanwhile.
        val bytes = try {
            Files.newByteChannel(real.first, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { ch ->
                val buf = java.nio.ByteBuffer.allocate(MAX_BYTES + 1)
                while (buf.hasRemaining() && ch.read(buf) >= 0) { /* fill */ }
                buf.array().copyOf(buf.position())
            }
        } catch (e: java.io.IOException) { return "cannot read ${args.path}: ${e.javaClass.simpleName}" }
        if (bytes.size > MAX_BYTES) return refused
        val lines = try { String(bytes, Charsets.UTF_8).removeSuffix("\n").lines() } catch (e: java.io.IOException) { return "cannot read ${args.path}: ${e.javaClass.simpleName}" }
        if (args.startLine == null && args.endLine == null) return lines.joinToString("\n")
        val from = (args.startLine ?: 1).coerceAtLeast(1)
        val to = (args.endLine ?: lines.size).coerceAtMost(lines.size)
        if (from > lines.size || from > to) return "${args.path} has ${lines.size} lines"
        return (from..to).joinToString("\n") { "$it: ${lines[it - 1]}" }
    }
}

private class ProjectMapTool(private val engine: ContextEngine, private val project: ProjectOf) : Tool {
    override val name = "project_map"
    override val description = "Overview of this project: languages, modules, folder layout, instruction files and key files. Pass a folder path for a deeper view."
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"Optional folder to zoom into"}}}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String = guarded { run(argumentsJson) }

    private suspend fun run(argumentsJson: String): String {
        val projects = inScope(engine, project) ?: return ContextEngine.NOT_READY
        val project = projects.firstOrNull() ?: return NO_PROJECT
        val args = runCatching { json.decodeFromString<MapArgs>(argumentsJson) }.getOrNull() ?: MapArgs()
        val map = ProjectMap.render(project, engine.fileSummariesOrNull(project.id) ?: return ContextEngine.NOT_READY, args.path)
        return (indexingNote(projects)?.plus("\n") ?: "") + map
    }
}

private class FindSymbolTool(private val engine: ContextEngine, private val project: ProjectOf) : Tool {
    override val name = "find_symbol"
    override val description = "Find where a class, function or other top-level declaration is defined (exact name, then case-insensitive, then prefix), plus the top places that mention it."
    override val parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"Identifier, e.g. ContextEngine or parse_args"}},"required":["name"]}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String = guarded { run(argumentsJson) }

    private suspend fun run(argumentsJson: String): String {
        val projects = inScope(engine, project) ?: return ContextEngine.NOT_READY
        val args = runCatching { json.decodeFromString<SymbolArgs>(argumentsJson) }.getOrNull()?.takeIf { it.name.isNotBlank() } ?: return "invalid arguments"
        if (projects.isEmpty()) return NO_PROJECT
        val ids = projects.map { it.id }
        val name = args.name.trim()
        val defs = engine.findSymbol(name, ids) ?: return ContextEngine.NOT_READY
        val defFiles = defs.map { it.projectId to it.path }.toSet()
        val mentions = (engine.search(name, ids, 10, vectors = false) ?: emptyList()).filter { (it.projectId to it.path) !in defFiles }.take(5)
        val note = indexingNote(projects)
        if (defs.isEmpty() && mentions.isEmpty()) return (note?.plus("\n") ?: "") + "No definition or mention of $name — try search_context or grep"
        val names = projects.associate { it.id to it.name }
        fun shown(pid: String, path: String) = if (projects.size > 1) "${names[pid]}:$path" else path
        return (note?.plus("\n") ?: "") + "definitions:\n" +
            (defs.joinToString("\n") { "  ${shown(it.projectId, it.path)}:${it.line} ${it.kind} ${it.name}" }.ifEmpty { "  (none found)" }) +
            "\nmentions:\n" + (mentions.joinToString("\n") { "  ${shown(it.projectId, it.path)}:${it.startLine}-${it.endLine} · ${it.label ?: "-"}" }.ifEmpty { "  (none)" })
    }
}

private class RelatedTool(private val engine: ContextEngine, private val project: ProjectOf) : Tool {
    override val name = "related"
    override val description = "Files connected to one file: what it imports and what imports it, its tests (or the code a test covers), and markdown links and backlinks."
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"Path as shown by search_context"}},"required":["path"]}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String = guarded { run(argumentsJson) }

    private suspend fun run(argumentsJson: String): String {
        val projects = inScope(engine, project) ?: return ContextEngine.NOT_READY
        val args = runCatching { json.decodeFromString<RelatedArgs>(argumentsJson) }.getOrNull()?.takeIf { it.path.isNotBlank() } ?: return "invalid arguments"
        if (projects.isEmpty()) return NO_PROJECT
        val refused = "not an indexed file in this project: ${args.path}"
        val (p, rel) = locate(projects, args.path) ?: return refused
        if (!(engine.fileIndexedOrNull(p.id, rel) ?: return ContextEngine.NOT_READY)) return refused
        val r = engine.related(p.id, rel) ?: return ContextEngine.NOT_READY
        val note = if (r.rebuilding) "\nrelations are being rebuilt — results may be incomplete" else ""
        if (r.isEmpty) return "No known relations for $rel (imports, tests and links resolve within this project only)$note"
        return listOf("imports" to r.imports, "imported by" to r.importedBy, "tests" to r.tests, "tested by" to r.testedBy,
            "links" to r.links, "backlinks" to r.backlinks).filter { it.second.isNotEmpty() }.joinToString("\n") { (k, v) ->
            "$k: " + v.take(CAP).joinToString(", ") + if (v.size > CAP) " (+${v.size - CAP} more)" else ""
        } + note
    }
}
