package dev.sophi.store.arcade

import com.arcadedb.database.Database
import com.arcadedb.database.DatabaseFactory
import com.arcadedb.database.Document
import com.arcadedb.index.TypeIndex
import com.arcadedb.index.vector.LSMVectorIndex
import com.arcadedb.schema.Type
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * ArcadeDB in this process. Schema creation and vectors go through the Java API (proven since
 * ADR-026: index creation in its own transaction; `putVector` calls the index's `put` because a
 * plain UPDATE doesn't trigger it). Everything else is the shared SQL in [SqlArcadeStore].
 */
class EmbeddedArcadeStore(private val local: Database) : SqlArcadeStore(local) {

    companion object {
        fun open(home: Path): EmbeddedArcadeStore {
            useSophiArcadeLogConfig()
            val factory = DatabaseFactory(home.resolve("arcadedb").toString())
            val db = if (factory.exists()) factory.open() else factory.create()
            return EmbeddedArcadeStore(db)
        }
    }

    override fun ensureSchema(vertexTypes: List<String>, edgeTypes: List<String>, documentTypes: List<String>) {
        local.transaction {
            val schema = local.schema
            vertexTypes.forEach { if (!schema.existsType(it)) schema.createVertexType(it) }
            edgeTypes.forEach { if (!schema.existsType(it)) schema.createEdgeType(it) }
            documentTypes.forEach { if (!schema.existsType(it)) schema.createDocumentType(it) }
            // UPSERT (used by upsertRecord) requires an index on the WHERE-matched property.
            (vertexTypes + documentTypes).forEach { ensureIdIndex(it) }
        }
    }

    private fun ensureIdIndex(type: String) {
        val docType = local.schema.getType(type)
        if (!docType.existsProperty("id")) docType.createProperty("id", Type.STRING)
        if (!local.schema.existsIndex("$type[id]")) local.command("sql", "CREATE INDEX ON $type (id) UNIQUE")
    }

    override fun putVector(vertexType: String, id: String, property: String, vector: FloatArray) {
        val indexName = "$vertexType[$property]"
        if (!local.schema.existsIndex(indexName)) {
            // Index creation is committed in its own transaction, separate from any data
            // write — ArcadeDB's LSM_VECTOR index build is deferred/async and races an
            // in-flight write in the same transaction (observed: "database closing" fallback
            // errors and an empty graph when both were combined).
            local.transaction {
                val docType = local.schema.getType(vertexType)
                if (!docType.existsProperty(property)) docType.createProperty(property, Type.ARRAY_OF_FLOATS)
                local.command(
                    "sql",
                    "CREATE INDEX ON $vertexType ($property) LSM_VECTOR METADATA " +
                        "{ dimensions: ${vector.size}, similarity: 'COSINE' }"
                )
            }
        }
        // A plain UPDATE on a pre-existing record does not trigger the LSM_VECTOR index's
        // incremental-add hook (observed: findNeighborsFromVector returns nothing for
        // vectors set this way, even though the property value itself round-trips fine via
        // a plain SELECT). The index's own `put(keys, rids)` is called directly instead.
        local.transaction {
            local.command("sql", "UPDATE $vertexType SET $property = ? WHERE id = ?", vector, id)
            val rid = local.query("sql", "SELECT FROM $vertexType WHERE id = ?", id).use { r -> r.next().identity.get() }
            vectorIndex(indexName).put(arrayOf<Any?>(vector), arrayOf(rid))
        }
    }

    private fun vectorIndex(indexName: String): LSMVectorIndex =
        (local.schema.getIndexByName(indexName) as TypeIndex).subIndexes.first() as LSMVectorIndex

    override fun nearestVectors(vertexType: String, property: String, query: FloatArray, k: Int): List<Scored> {
        val indexName = "$vertexType[$property]"
        if (!local.schema.existsIndex(indexName)) return emptyList()
        return vectorIndex(indexName).findNeighborsFromVector(query, k).map { pair ->
            val doc = local.lookupByRID(pair.first, true) as Document
            Scored(doc.getString("id"), pair.second.toDouble())
        }
    }

    override fun close() = local.close()
}

internal const val LOG_CONFIG_PROPERTY = "java.util.logging.config.file"

/**
 * Points ArcadeDB's logging at Sophi's config (log file under the home dir, console at WARNING)
 * instead of the engine's own, which logs to ./log in whatever directory the host was started in.
 * ArcadeDB reads [LOG_CONFIG_PROPERTY] before looking on the classpath, so this holds whatever the
 * jar order — the packaged companion lists arcadedb-engine ahead of sophi-store. Must run before
 * ArcadeDB first logs; a host that already chose a logging config keeps it.
 */
internal fun useSophiArcadeLogConfig() {
    if (System.getProperty(LOG_CONFIG_PROPERTY) != null) return
    val file = Files.createTempFile("sophi-arcadedb-log", ".properties").also { it.toFile().deleteOnExit() }
    EmbeddedArcadeStore::class.java.getResourceAsStream("/sophi-arcadedb-log.properties")!!
        .use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
    System.setProperty(LOG_CONFIG_PROPERTY, file.toString())
}
