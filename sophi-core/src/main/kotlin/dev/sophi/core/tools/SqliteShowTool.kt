package dev.sophi.core.tools

import dev.sophi.core.permissions.PermissionSubject
import dev.sophi.core.permissions.pathSubject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile

private const val MAX_CELL = 200
private val HEADER = "SQLite format 3\u0000".toByteArray()

@Serializable
private data class SqliteShowArgs(val path: String, val table: String? = null, val limit: Int = 50, val offset: Int = 0)

/**
 * Looks at a SQLite file in the workspace through the system `sqlite3`, opened read-only (and `-safe` where the
 * installed version has it). The model never writes SQL: it names a table, which must be one `sqlite_master` lists.
 */
class SqliteShowTool(private val root: Path = Paths.get("").toAbsolutePath(), private val sqlite3: String = "sqlite3") : Tool {
    override val name = "sqlite_show"
    override val description = "Show a SQLite database file in the working directory: with only path, its tables and views " +
        "(schema and row counts); with a table, its columns and one page of rows. Read-only, no SQL."
    override val parametersJson = """
        {"type":"object","properties":{"path":{"type":"string","description":"Path to the database file, relative to the working directory"},"table":{"type":"string","description":"Table or view to page through; omit to list them"},"limit":{"type":"integer","description":"Rows per page (default 50, max 200)"},"offset":{"type":"integer","description":"Rows to skip (default 0)"}},"required":["path"]}
    """.trimIndent()
    override val deferred = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun permissionSubjects(argumentsJson: String): List<PermissionSubject>? =
        runCatching { json.decodeFromString<SqliteShowArgs>(argumentsJson) }.getOrNull()
            ?.let { pathSubject(root, root.resolve(it.path).normalize()) }?.let { listOf(it) }

    /** `-safe` (3.37+) also blocks ATTACH, extensions and shell escapes. Null until sqlite3 has been found. */
    private var flags: List<String>? = null

    private suspend fun flags(): List<String>? = flags ?: run(listOf("-version"))?.takeIf { it.first == 0 }?.let { (_, out) ->
        val (major, minor) = Regex("""^(\d+)\.(\d+)""").find(out.trim())?.destructured ?: return@let null
        val safe = major.toInt() > 3 || (major.toInt() == 3 && minor.toInt() >= 37)
        (listOf("-readonly", "-json") + if (safe) listOf("-safe") else emptyList()).also { flags = it }
    }

    private suspend fun run(args: List<String>): Pair<Int, String>? = try {
        SystemDesktop.runCommand(listOf(sqlite3) + args, null, timeoutMs = 30_000)
    } catch (_: IOException) { null }

    override suspend fun execute(argumentsJson: String): String {
        val args = json.decodeFromString<SqliteShowArgs>(argumentsJson)
        val real = try { root.resolve(args.path).normalize().toRealPath() } catch (_: IOException) { return "Error: no file at ${args.path}" }
        if (!real.startsWith(root.toRealPath())) return "Error: ${args.path} is outside the working directory"
        if (!real.isRegularFile()) return "Error: ${args.path} is not a file"
        val head = Files.newInputStream(real).use { it.readNBytes(HEADER.size) }
        if (!head.contentEquals(HEADER)) return "Error: not a SQLite database: ${args.path}"
        val flags = flags() ?: return "Error: sqlite3 is not installed (not found on PATH)"

        val query: suspend (String) -> List<JsonObject> = { sql ->
            val (code, out) = run(flags + listOf(real.toString(), sql)) ?: error("sqlite3 is not installed")
            check(code == 0) { "sqlite3 failed: ${out.trim()}" }
            if (out.isBlank()) emptyList() else json.parseToJsonElement(out).jsonArray.map { it.jsonObject }
        }
        return try {
            val objects = query("""SELECT type, name, sql FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite\_%' ESCAPE '\' ORDER BY name""")
            if (args.table == null) list(objects, query) else page(args, args.table, objects.map { it.text("name") }, query)
        } catch (e: IllegalStateException) { "Error: ${e.message}" }
    }

    private suspend fun list(objects: List<JsonObject>, query: suspend (String) -> List<JsonObject>): String {
        if (objects.isEmpty()) return "No tables or views."
        val tables = objects.filter { it.text("type") == "table" }.map { it.text("name") }
        // One union for every count; a table sqlite3 can't count (a virtual table without its module) drops the counts only.
        val counts = if (tables.isEmpty()) emptyMap() else try {
            query(tables.joinToString(" UNION ALL ") { "SELECT ${literal(it)} AS name, count(*) AS n FROM ${ident(it)}" })
                .associate { it.text("name") to it.text("n") }
        } catch (_: IllegalStateException) { emptyMap() }
        return objects.joinToString("\n") { o ->
            val name = o.text("name")
            val n = counts[name]?.let { " ($it rows)" } ?: ""
            "${o.text("type")} $name$n\n  ${o.text("sql").replace("\n", "\n  ")}"
        }
    }

    private suspend fun page(args: SqliteShowArgs, table: String, names: List<String>, query: suspend (String) -> List<JsonObject>): String {
        if (table !in names) return "Error: no table or view named \"$table\". Tables and views: ${names.joinToString(", ")}"
        val limit = args.limit.coerceIn(1, 200)
        val offset = args.offset.coerceAtLeast(0)
        val columns = query("SELECT name FROM pragma_table_info(${literal(table)})").map { it.text("name") }
        val total = query("SELECT count(*) AS n FROM ${ident(table)}").single().text("n")
        val rows = query("SELECT * FROM ${ident(table)} LIMIT $limit OFFSET $offset")
        val head = if (rows.isEmpty()) "$table: no rows at offset $offset of $total" else "$table: rows ${offset + 1}-${offset + rows.size} of $total"
        return (listOf(head, columns.joinToString(" | ")) + rows.map { r -> r.values.joinToString(" | ") { cell(it) } }).joinToString("\n")
    }

    // ponytail: sqlite3's json mode prints blobs as mangled text; render them as hex if blobs ever matter.
    private fun cell(v: JsonElement): String {
        val s = if (v is JsonNull) "NULL" else (v as? JsonPrimitive)?.content ?: v.toString()
        val flat = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return if (flat.length <= MAX_CELL) flat else flat.take(MAX_CELL) + "…[+${flat.length - MAX_CELL} chars]"
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun ident(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
    private fun literal(s: String) = "'" + s.replace("'", "''") + "'"
}
