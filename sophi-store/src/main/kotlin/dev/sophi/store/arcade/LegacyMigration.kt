package dev.sophi.store.arcade

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
 * (ADR-041 M6). Copy, verify counts, write a [MARKER], rename the old directory aside — never delete
 * it. No marker means an earlier copy didn't finish: start over from an empty target. Once marked
 * (also when there was nothing to move), a stray old directory is only ever renamed aside, never copied
 * over live data.
 */
object LegacyMigration {
    const val MARKER = "MigrationMarker"

    /** Never cleared, counted or copied: the host's per-database schema marker and our own. */
    private val RESERVED = setOf(ArcadeHost.SCHEMA_TYPE, MARKER)

    sealed interface Result {
        data object NothingToMigrate : Result
        data object AlreadyMigrated : Result
        data class Migrated(val counts: Map<String, Int>) : Result
        data class SourceUnavailable(val reason: String) : Result
    }

    fun isMarked(target: ArcadeStore): Boolean {
        target.ensureSchema(documentTypes = listOf(MARKER))
        return target.documents(MARKER).isNotEmpty()
    }

    fun run(target: ArcadeStore, legacyHome: Path, layout: LegacyLayout, now: () -> Long = System::currentTimeMillis): Result {
        val old = legacyHome.resolve("arcadedb")
        if (isMarked(target)) {
            if (Files.exists(old)) moveAside(old, now())   // a stray directory recreated by an older build
            return Result.AlreadyMigrated
        }
        if (!Files.isDirectory(old)) {
            mark(target, old, now(), emptyMap())
            return Result.NothingToMigrate
        }
        val source = try { EmbeddedArcadeStore.open(legacyHome) } catch (e: Exception) {
            return Result.SourceUnavailable(e.message ?: e.javaClass.simpleName)
        }
        val counts = source.use { copy(it, target, layout) }
        mark(target, old, now(), counts)
        moveAside(old, now())
        return Result.Migrated(counts)
    }

    // `fromPath`, not `from`: the generic upsert does not quote names and FROM is an SQL keyword.
    private fun mark(target: ArcadeStore, old: Path, at: Long, counts: Map<String, Int>) =
        target.upsertDocument(MARKER, "legacy", mapOf(
            "fromPath" to old.toString(), "at" to at, "counts" to counts.entries.joinToString(",") { "${it.key}=${it.value}" }
        ))

    private fun copy(src: ArcadeStore, target: ArcadeStore, raw: LegacyLayout): Map<String, Int> {
        val layout = LegacyLayout(
            raw.vertexTypes - RESERVED, raw.edgeTypes.filterKeys { it !in RESERVED }, raw.documentTypes - RESERVED
        )
        val present = src.query("SELECT name FROM schema:types").map { it["name"] as String }.toSet()
        target.ensureSchema(layout.vertexTypes, layout.edgeTypes.keys.toList(), layout.documentTypes)
        // No marker: whatever is here is a partial earlier copy. Edges first, then what they join.
        layout.edgeTypes.keys.forEach { target.deleteAll(it) }
        (layout.vertexTypes + layout.documentTypes).forEach { target.deleteAll(it) }
        val counts = linkedMapOf<String, Int>()
        layout.vertexTypes.filter { it in present }.forEach { t ->
            counts[t] = src.queryVertices(t).count { row -> (row["id"] as? String)?.also { target.upsertVertex(t, it, row.properties()) } != null }
        }
        layout.documentTypes.filter { it in present }.forEach { t ->
            counts[t] = src.documents(t).count { row -> (row["id"] as? String)?.also { target.upsertDocument(t, it, row.properties()) } != null }
        }
        layout.edgeTypes.filter { (e, v) -> e in present && v in present }.forEach { (e, v) ->
            val ids = src.query("SELECT id FROM $v").mapNotNull { it["id"] as? String }.toSet()
            val rows = src.edges(e).filter { it["fromId"] in ids && it["toId"] in ids }.distinctBy { it["fromId"] to it["toId"] }
            rows.forEach { target.upsertEdge(e, v, it["fromId"] as String, it["toId"] as String, it.properties() - "fromId" - "toId") }
            counts[e] = rows.size
        }
        counts.forEach { (t, n) ->
            val found = (target.query("SELECT count(*) AS n FROM $t").single()["n"] as Number).toInt()
            check(found == n) { "moving $t to the shared store: copied $n, found $found — will retry next start" }
        }
        return counts
    }

    private fun Map<String, Any?>.properties(): Map<String, Any?> = filterKeys { !it.startsWith("@") && it != "id" }

    /** Files.move refuses an existing target, so nothing is ever overwritten. */
    private fun moveAside(old: Path, ts: Long) {
        Files.move(old, old.resolveSibling("arcadedb.migrated-$ts"))
    }
}

/** Opens [database] on the shared server at [root], adopting `<legacyHome>/arcadedb` the first time (host only). */
object SharedStore {
    /** Throws IllegalStateException (one-line message) when legacy data can't be adopted this run; IncompatibleHostException on schema mismatch. */
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
            // Only the host migrates; never start an empty store over data that hasn't moved.
            if (Files.isDirectory(old) && !LegacyMigration.isMarked(store))
                throw IllegalStateException("$database data at $old hasn't moved to the shared store yet — quit the other Sophi apps, then restart this one")
            return
        }
        when (val r = LegacyMigration.run(store, legacyHome, layout)) {
            is LegacyMigration.Result.SourceUnavailable -> throw IllegalStateException(
                "couldn't open the existing $database data at $old (${r.reason}) — an older Sophi may still be running; quit it, then restart this one")
            is LegacyMigration.Result.Migrated -> System.err.println("$database: moved ${r.counts} from $old to the shared store")
            else -> Unit
        }
    }
}
