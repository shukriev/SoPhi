package dev.sophi.context

import dev.sophi.store.arcade.ArcadeStore

data class SymbolHit(val projectId: String, val path: String, val line: Int, val kind: String, val name: String, val role: String)

data class RelatedFiles(
    val imports: List<String>, val importedBy: List<String>, val tests: List<String>,
    val testedBy: List<String>, val links: List<String>, val backlinks: List<String>,
    /** The project's relationsAt is null: edges are being rebuilt, lists may be incomplete. */
    val rebuilding: Boolean = false
) {
    val isEmpty: Boolean get() = listOf(imports, importedBy, tests, testedBy, links, backlinks).all { it.isEmpty() }
}

/** Reads the M5 graph documents (ADR-041 §10). Parameters only — names are never interpolated into SQL. */
internal class Graph(private val store: ArcadeStore) {
    private val fields = "projectId, path, line, kind, name, role"

    fun findSymbol(name: String, projectIds: List<String>, limit: Int): List<SymbolHit> {
        if (name.isBlank() || projectIds.isEmpty()) return emptyList()
        val lower = name.lowercase()
        // fetch limit*3 so test-file definitions can't crowd a source definition out before the sort
        val fetch = limit * 3
        val rows = store.query("SELECT $fields FROM ${ContextSchema.SYMBOL} WHERE name = ? AND projectId IN ? LIMIT $fetch", name, projectIds)
            .ifEmpty { store.query("SELECT $fields FROM ${ContextSchema.SYMBOL} WHERE lname = ? AND projectId IN ? LIMIT $fetch", lower, projectIds) }
            .ifEmpty { store.query("SELECT $fields FROM ${ContextSchema.SYMBOL} WHERE lname >= ? AND lname < ? AND projectId IN ? ORDER BY lname LIMIT $fetch",
                lower, lower + "￿", projectIds) }
        return rows.map { r -> SymbolHit(r["projectId"] as String, r["path"] as String, (r["line"] as Number).toInt(),
            r["kind"] as String, r["name"] as String, r["role"] as String) }
            .sortedWith(compareBy({ it.role == Role.TEST.name }, { it.path }, { it.line }))
            .take(limit)
    }

    fun related(projectId: String, path: String): RelatedFiles {
        val id = ContextSchema.fileId(projectId, path)
        val out = store.query("SELECT kind, toPath FROM ${ContextSchema.RELATION} WHERE fromFileId = ?", id)
        val inc = store.query("SELECT kind, fromPath FROM ${ContextSchema.RELATION} WHERE toFileId = ?", id)
        val rebuilding = store.query("SELECT relationsAt FROM ${ContextSchema.PROJECT} WHERE id = ?", projectId).firstOrNull()?.get("relationsAt") == null
        fun pick(rows: List<Map<String, Any?>>, kind: String, field: String) =
            rows.filter { it["kind"] == kind }.map { it[field] as String }.distinct().sorted()
        return RelatedFiles(
            imports = pick(out, Relations.IMPORTS, "toPath"), importedBy = pick(inc, Relations.IMPORTS, "fromPath"),
            tests = pick(out, Relations.TESTS, "toPath"), testedBy = pick(inc, Relations.TESTS, "fromPath"),
            links = pick(out, Relations.LINKS, "toPath"), backlinks = pick(inc, Relations.LINKS, "fromPath"), rebuilding = rebuilding)
    }
}
