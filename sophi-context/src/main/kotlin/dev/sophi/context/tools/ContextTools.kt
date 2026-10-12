package dev.sophi.context.tools

import dev.sophi.context.ContextEngine
import dev.sophi.context.ProjectInfo
import dev.sophi.context.ProjectMap
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path

/** Spec invariant 3: no exception escapes a context tool. */
private inline fun guarded(block: () -> String): String =
    try { block() } catch (e: Exception) { "context tool failed: ${e.javaClass.simpleName}" }

private val json = Json { ignoreUnknownKeys = true }

@Serializable private data class SearchArgs(val query: String = "", val limit: Int = 8)
@Serializable private data class ReadArgs(val path: String = "", val startLine: Int? = null, val endLine: Int? = null)
@Serializable private data class MapArgs(val path: String? = null)

/** search_context / read_context / project_map (spec §6.6). [scope] = project ids for the current session. */
fun contextTools(engine: ContextEngine, scope: () -> List<String>): List<Tool> = listOf(
    SearchContextTool(engine, scope), ReadContextTool(engine, scope), ProjectMapTool(engine, scope)
)

private fun inScope(engine: ContextEngine, scope: () -> List<String>): List<ProjectInfo>? {
    if (!engine.ready) return null
    val ids = scope().toSet()
    return engine.projectsOrNull()?.filter { it.id in ids }
}

private class SearchContextTool(private val engine: ContextEngine, private val scope: () -> List<String>) : Tool {
    override val name = "search_context"
    override val description = "Find the files and code most relevant to a question in this project's index (ranked; faster and better than grep for 'where is X handled' questions). Returns path:lines, the symbol or heading, and a snippet."
    override val parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"What you're looking for, in words or identifiers"},"limit":{"type":"integer","description":"Max results (default 8, max 20)"}},"required":["query"]}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String = guarded { run(argumentsJson) }

    private fun run(argumentsJson: String): String {
        val projects = inScope(engine, scope) ?: return ContextEngine.NOT_READY
        val args = runCatching { json.decodeFromString<SearchArgs>(argumentsJson) }.getOrNull() ?: return "invalid arguments"
        if (projects.isEmpty()) return "No indexed project here — run `sophi context add <dir>`."
        val hits = engine.search(args.query, projects.map { it.id }, args.limit.coerceIn(1, 20)) ?: return ContextEngine.NOT_READY
        if (hits.isEmpty()) return "No matches — try other words, or grep"
        val names = projects.associate { it.id to it.name }
        return hits.joinToString("\n") { h ->
            val path = if (projects.size > 1) "${names[h.projectId]}:${h.path}" else h.path
            "$path:${h.startLine}-${h.endLine} · ${h.label ?: "-"} · ${h.role.lowercase()}\n" + h.snippet.lines().joinToString("\n") { "    $it" }
        }
    }
}

private class ReadContextTool(private val engine: ContextEngine, private val scope: () -> List<String>) : Tool {
    override val name = "read_context"
    override val description = "Read an indexed file of this project (optionally a line range). Use after search_context."
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"Path as shown by search_context"},"startLine":{"type":"integer"},"endLine":{"type":"integer"}},"required":["path"]}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String = guarded { run(argumentsJson) }

    private fun run(argumentsJson: String): String {
        val projects = inScope(engine, scope) ?: return ContextEngine.NOT_READY
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
        if (attrs.isSymbolicLink || !attrs.isRegularFile || attrs.size() > 1_000_000) return refused
        val real = try { file.toRealPath() to root.toRealPath() } catch (e: java.io.IOException) { return refused }
        if (!real.first.startsWith(real.second)) return refused
        val lines = try { String(Files.readAllBytes(file), Charsets.UTF_8).removeSuffix("\n").lines() } catch (e: java.io.IOException) { return "cannot read ${args.path}: ${e.javaClass.simpleName}" }
        if (args.startLine == null && args.endLine == null) return lines.joinToString("\n")
        val from = (args.startLine ?: 1).coerceAtLeast(1)
        val to = (args.endLine ?: lines.size).coerceAtMost(lines.size)
        if (from > lines.size || from > to) return "${args.path} has ${lines.size} lines"
        return (from..to).joinToString("\n") { "$it: ${lines[it - 1]}" }
    }

    private fun locate(projects: List<ProjectInfo>, path: String): Pair<ProjectInfo, String>? {
        val named = path.substringBefore(':', "")
        projects.firstOrNull { it.name == named }?.let { return it to path.substringAfter(':') }
        return projects.firstOrNull()?.let { it to path }
    }
}

private class ProjectMapTool(private val engine: ContextEngine, private val scope: () -> List<String>) : Tool {
    override val name = "project_map"
    override val description = "Overview of this project: languages, modules, folder layout, instruction files and key files. Pass a folder path for a deeper view."
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"Optional folder to zoom into"}}}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String = guarded { run(argumentsJson) }

    private fun run(argumentsJson: String): String {
        val projects = inScope(engine, scope) ?: return ContextEngine.NOT_READY
        val project = projects.firstOrNull() ?: return "No indexed project here — run `sophi context add <dir>`."
        val args = runCatching { json.decodeFromString<MapArgs>(argumentsJson) }.getOrNull() ?: MapArgs()
        return ProjectMap.render(project, engine.fileSummariesOrNull(project.id) ?: return ContextEngine.NOT_READY, args.path)
    }
}
