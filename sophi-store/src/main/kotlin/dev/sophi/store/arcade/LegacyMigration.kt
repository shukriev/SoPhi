package dev.sophi.store.arcade

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The types a pre-M6 embedded database holds; edges map to the vertex type they join. */
data class LegacyLayout(
    val vertexTypes: List<String> = emptyList(),
    val edgeTypes: Map<String, String> = emptyMap(),
    val documentTypes: List<String> = emptyList()
)

/**
 * Moves a database that lived embedded at `<legacyHome>/arcadedb` onto the shared server, once
 * (ADR-041 M6). Copy, verify counts against the source, write a [MARKER], rename the old directory
 * aside — never delete it. Once marked (also when there was nothing to move), a stray old directory is
 * only ever renamed aside, never copied over live data. The target is cleared only when it is empty or
 * holds the [IN_PROGRESS] note an interrupted copy left; any other data in it is never touched.
 */
object LegacyMigration {
    const val MARKER = "MigrationMarker"
    const val IN_PROGRESS = "MigrationInProgress"

    /** Never cleared, counted or copied: the host's per-database schema marker and our own. */
    private val RESERVED = setOf(ArcadeHost.SCHEMA_TYPE, MARKER, IN_PROGRESS)
    private const val ID = "legacy"

    sealed interface Result {
        data object NothingToMigrate : Result
        data object AlreadyMigrated : Result
        data class Migrated(val counts: Map<String, Int>, val skipped: Map<String, Int> = emptyMap()) : Result
        data class SourceUnavailable(val reason: String) : Result
        /** Legacy data exists but the target already holds other data: neither is touched. */
        data object TargetNotEmpty : Result
    }

    fun isMarked(target: ArcadeStore): Boolean {
        target.ensureSchema(documentTypes = listOf(MARKER))
        return target.documents(MARKER).isNotEmpty()
    }

    fun run(target: ArcadeStore, legacyHome: Path, layout: LegacyLayout, now: () -> Long = System::currentTimeMillis): Result {
        val old = legacyHome.resolve("arcadedb")
        val ts = now()
        if (isMarked(target)) {
            if (Files.exists(old)) moveAside(legacyHome, ts)   // a stray directory recreated by an older build
            return Result.AlreadyMigrated
        }
        if (!Files.isDirectory(old)) {
            mark(target, old, ts, emptyMap(), emptyMap())
            return Result.NothingToMigrate
        }
        target.ensureSchema(documentTypes = listOf(IN_PROGRESS))
        if (target.documents(IN_PROGRESS).isEmpty() && !isEmpty(target)) return Result.TargetNotEmpty
        val source = try { EmbeddedArcadeStore.open(legacyHome) } catch (e: Exception) {
            return Result.SourceUnavailable(e.message?.lineSequence()?.first() ?: e.javaClass.simpleName)
        }
        val (counts, skipped) = source.use { copy(it, target, layout, ts) }
        target.transaction {
            mark(target, old, ts, counts, skipped)
            target.deleteDocument(IN_PROGRESS, ID)
        }
        moveAside(legacyHome, ts)
        return Result.Migrated(counts, skipped)
    }

    /** No rows in any type but the reserved ones. */
    private fun isEmpty(target: ArcadeStore): Boolean =
        target.query("SELECT name FROM schema:types").map { it["name"] as String }.filter { it !in RESERVED }
            .all { count(target, it) == 0 }

    private fun count(store: ArcadeStore, type: String): Int =
        (store.query("SELECT count(*) AS n FROM $type").single()["n"] as Number).toInt()

    private fun Map<String, Int>.joined() = entries.joinToString(",") { "${it.key}=${it.value}" }

    // `fromPath`, not `from`: the generic upsert does not quote names and FROM is an SQL keyword.
    private fun mark(target: ArcadeStore, old: Path, at: Long, counts: Map<String, Int>, skipped: Map<String, Int>) =
        target.upsertDocument(MARKER, ID, mapOf(
            "fromPath" to old.toString(), "at" to at, "counts" to counts.joined(), "skipped" to skipped.joined()
        ))

    private fun copy(src: ArcadeStore, target: ArcadeStore, raw: LegacyLayout, ts: Long): Pair<Map<String, Int>, Map<String, Int>> {
        val layout = LegacyLayout(
            raw.vertexTypes - RESERVED, raw.edgeTypes.filterKeys { it !in RESERVED }, raw.documentTypes - RESERVED
        )
        val present = src.query("SELECT name FROM schema:types").map { it["name"] as String }.toSet()
        // Written before the first data write, so a crash leaves proof that what follows is ours to clear.
        target.upsertDocument(IN_PROGRESS, ID, mapOf("at" to ts))
        target.ensureSchema(layout.vertexTypes, layout.edgeTypes.keys.toList(), layout.documentTypes)
        // Edges first, then what they join.
        layout.edgeTypes.keys.forEach { target.deleteAll(it) }
        (layout.vertexTypes + layout.documentTypes).forEach { target.deleteAll(it) }
        val counts = linkedMapOf<String, Int>()
        val skipped = linkedMapOf<String, Int>()
        layout.vertexTypes.filter { it in present }.forEach { t ->
            counts[t] = src.queryVertices(t).count { row -> (row["id"] as? String)?.also { target.upsertVertex(t, it, row.properties()) } != null }
        }
        layout.documentTypes.filter { it in present }.forEach { t ->
            counts[t] = src.documents(t).count { row -> (row["id"] as? String)?.also { target.upsertDocument(t, it, row.properties()) } != null }
        }
        counts.forEach { (t, n) ->
            val inSource = count(src, t)
            check(inSource == n) { "moving $t to the shared store: source has $inSource, copied $n (rows without an id?) — will retry next start" }
        }
        layout.edgeTypes.filter { (e, v) -> e in present && v in present }.forEach { (e, v) ->
            val ids = src.query("SELECT id FROM $v").mapNotNull { it["id"] as? String }.toSet()
            val rows = src.edges(e).filter { it["fromId"] in ids && it["toId"] in ids }.distinctBy { it["fromId"] to it["toId"] }
            rows.forEach { target.upsertEdge(e, v, it["fromId"] as String, it["toId"] as String, it.properties() - "fromId" - "toId") }
            counts[e] = rows.size
            (count(src, e) - rows.size).takeIf { it > 0 }?.let { skipped[e] = it }
        }
        counts.forEach { (t, n) ->
            val found = count(target, t)
            check(found == n) { "moving $t to the shared store: copied $n, found $found — will retry next start" }
        }
        return counts to skipped
    }

    private fun Map<String, Any?>.properties(): Map<String, Any?> = filterKeys { !it.startsWith("@") && it != "id" }

    /**
     * Renames `<legacyHome>/arcadedb` aside, never overwriting (Files.move refuses an existing target).
     * Skipped with one line, to retry next start, while another process holds it or the rename fails.
     */
    private fun moveAside(legacyHome: Path, ts: Long) {
        val old = legacyHome.resolve("arcadedb")
        try { EmbeddedArcadeStore.open(legacyHome).close() } catch (e: Exception) {
            System.err.println("legacy $old is in use (an older Sophi?); left in place, will move it aside next start")
            return
        }
        try { Files.move(old, old.resolveSibling("arcadedb.migrated-$ts")) } catch (e: IOException) {
            System.err.println("couldn't move legacy $old aside (${e.message}); left in place, will retry next start")
        }
    }
}

/** Opens [database] on the shared server at [root], adopting `<legacyHome>/arcadedb` the first time (host only). */
object SharedStore {
    /** True when [a] and [b] are the same directory however spelled (symlink, case on a case-insensitive disk); a path that doesn't exist yet compares absolute+normalized. */
    fun samePath(a: Path, b: Path): Boolean = canonical(a) == canonical(b)
    private fun canonical(p: Path): Path = runCatching { p.toRealPath() }.getOrElse { p.toAbsolutePath().normalize() }

    /**
     * Throws IllegalStateException (one-line message) when legacy data can't be adopted this run; IncompatibleHostException on schema mismatch.
     *
     * Every opener of a [database] that has a legacy home must pass the same non-null [legacyHome]: the migration's
     * safety depends on it. An opener passing null skips both the host's migration and the client's refusal to run
     * over unmigrated data, so it could start an empty store and write into it before the legacy data moves.
     */
    fun open(root: Path, database: String, schemaVersion: Int, legacyHome: Path? = null, layout: LegacyLayout = LegacyLayout()): FailoverArcadeStore {
        val lease = SharedArcadeHost.acquire(root)
        val store = try { FailoverArcadeStore(lease, database, schemaVersion) } catch (e: Throwable) { lease.close(); throw e }
        try {
            if (legacyHome != null) adoptLegacy(store, database, legacyHome, layout)
            return store
        } catch (e: Throwable) {
            store.close(); throw e
        }
    }

    private fun adoptLegacy(store: FailoverArcadeStore, database: String, legacyHome: Path, layout: LegacyLayout) {
        val old = legacyHome.resolve("arcadedb")
        if (store.role == Role.CLIENT) {
            // Only the host migrates. With nothing to move, mark it so a later stray dir is never copied over our writes.
            if (!Files.isDirectory(old)) { LegacyMigration.run(store, legacyHome, layout); return }
            // Never start an empty store over data that hasn't moved.
            if (!LegacyMigration.isMarked(store))
                throw IllegalStateException("$database data at $old hasn't moved to the shared store yet — quit the other Sophi apps, then restart this one")
            return
        }
        val r = try { LegacyMigration.run(store, legacyHome, layout) } catch (e: IncompatibleHostException) { throw e } catch (e: Exception) {
            throw IllegalStateException("couldn't move $database data at $old to the shared store " +
                "(${e.message?.lineSequence()?.first() ?: e.javaClass.simpleName}) — will retry next start", e)
        }
        when (r) {
            is LegacyMigration.Result.SourceUnavailable -> throw IllegalStateException(
                "couldn't open the existing $database data at $old (${r.reason}) — an older Sophi may still be running; quit it, then restart this one")
            is LegacyMigration.Result.Migrated -> System.err.println(
                "$database: moved ${r.counts} from $old to the shared store" + if (r.skipped.isEmpty()) "" else ", skipped edges ${r.skipped}")
            LegacyMigration.Result.TargetNotEmpty -> System.err.println("legacy $old left in place; shared $database already has data")
            else -> Unit
        }
    }
}
