package dev.sophi.memory.store.arcade

import com.arcadedb.database.Database
import com.arcadedb.database.DatabaseFactory
import com.arcadedb.database.Document
import com.arcadedb.index.TypeIndex
import com.arcadedb.index.vector.LSMVectorIndex
import com.arcadedb.schema.Type
import java.nio.file.Path

/**
 * ArcadeDB doesn't route the documented `vector.neighbors(...)` SQL function to a live
 * implementation in 26.5.1 (throws "Unknown method name: neighbors" at runtime) — nearest-
 * neighbor search goes through the direct Java API instead: LSMVectorIndex.findNeighborsFromVector.
 */
class EmbeddedArcadeStore(private val db: Database) : ArcadeStore {

    companion object {
        fun open(home: Path): EmbeddedArcadeStore {
            val factory = DatabaseFactory(home.resolve("arcadedb").toString())
            val db = if (factory.exists()) factory.open() else factory.create()
            return EmbeddedArcadeStore(db)
        }
    }

    override fun ensureSchema(vertexTypes: List<String>, edgeTypes: List<String>, documentTypes: List<String>) {
        db.transaction {
            val schema = db.schema
            vertexTypes.forEach { if (!schema.existsType(it)) schema.createVertexType(it) }
            edgeTypes.forEach { if (!schema.existsType(it)) schema.createEdgeType(it) }
            documentTypes.forEach { if (!schema.existsType(it)) schema.createDocumentType(it) }
            // UPSERT (used by upsertRecord) requires an index on the WHERE-matched property.
            (vertexTypes + documentTypes).forEach { ensureIdIndex(it) }
        }
    }

    private fun ensureIdIndex(type: String) {
        val docType = db.schema.getType(type)
        if (!docType.existsProperty("id")) docType.createProperty("id", Type.STRING)
        if (!db.schema.existsIndex("$type[id]")) db.command("sql", "CREATE INDEX ON $type (id) UNIQUE")
    }

    override fun upsertVertex(type: String, id: String, properties: Map<String, Any?>) = upsertRecord(type, id, properties)
    override fun getVertex(type: String, id: String): Map<String, Any?>? = getById(type, id)
    override fun queryVertices(type: String): List<Map<String, Any?>> = queryAll(type)
    override fun deleteVertex(type: String, id: String) = deleteById(type, id)

    override fun upsertEdge(type: String, vertexType: String, fromId: String, toId: String, properties: Map<String, Any?>) {
        db.transaction {
            db.command("sql", "DELETE FROM $type WHERE fromId = ? AND toId = ?", fromId, toId)
            val all = mapOf("fromId" to fromId, "toId" to toId) + properties
            val setClause = all.keys.joinToString(", ") { "$it = ?" }
            db.command(
                "sql",
                "CREATE EDGE $type FROM (SELECT FROM $vertexType WHERE id = ?) TO (SELECT FROM $vertexType WHERE id = ?) SET $setClause",
                fromId, toId, *all.values.toTypedArray()
            )
        }
    }

    override fun edges(type: String): List<Map<String, Any?>> = queryAll(type)

    override fun deleteEdge(type: String, fromId: String, toId: String) {
        db.transaction { db.command("sql", "DELETE FROM $type WHERE fromId = ? AND toId = ?", fromId, toId) }
    }

    override fun putVector(vertexType: String, id: String, property: String, vector: FloatArray) {
        val indexName = "$vertexType[$property]"
        if (!db.schema.existsIndex(indexName)) {
            // Index creation is committed in its own transaction, separate from any data
            // write — ArcadeDB's LSM_VECTOR index build is deferred/async and races an
            // in-flight write in the same transaction (observed: "database closing" fallback
            // errors and an empty graph when both were combined).
            db.transaction {
                val docType = db.schema.getType(vertexType)
                if (!docType.existsProperty(property)) docType.createProperty(property, Type.ARRAY_OF_FLOATS)
                db.command(
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
        db.transaction {
            db.command("sql", "UPDATE $vertexType SET $property = ? WHERE id = ?", vector, id)
            val rid = db.query("sql", "SELECT FROM $vertexType WHERE id = ?", id).use { r -> r.next().identity.get() }
            vectorIndex(indexName).put(arrayOf<Any?>(vector), arrayOf(rid))
        }
    }

    private fun vectorIndex(indexName: String): LSMVectorIndex =
        (db.schema.getIndexByName(indexName) as TypeIndex).subIndexes.first() as LSMVectorIndex

    override fun nearestVectors(vertexType: String, property: String, query: FloatArray, k: Int): List<Scored> {
        val indexName = "$vertexType[$property]"
        if (!db.schema.existsIndex(indexName)) return emptyList()
        return vectorIndex(indexName).findNeighborsFromVector(query, k).map { pair ->
            val doc = db.lookupByRID(pair.first, true) as Document
            Scored(doc.getString("id"), pair.second.toDouble())
        }
    }

    override fun upsertDocument(type: String, id: String, properties: Map<String, Any?>) = upsertRecord(type, id, properties)
    override fun documents(type: String): List<Map<String, Any?>> = queryAll(type)
    override fun deleteDocument(type: String, id: String) = deleteById(type, id)

    override fun deleteAll(type: String) {
        db.transaction { db.command("sql", "DELETE FROM $type") }
    }

    private fun upsertRecord(type: String, id: String, properties: Map<String, Any?>) {
        db.transaction {
            val all = mapOf("id" to id) + properties
            val setClause = all.keys.joinToString(", ") { "$it = ?" }
            db.command("sql", "UPDATE $type SET $setClause UPSERT WHERE id = ?", *all.values.toTypedArray(), id)
        }
    }

    private fun getById(type: String, id: String): Map<String, Any?>? {
        val rs = db.query("sql", "SELECT FROM $type WHERE id = ?", id)
        return rs.use { r -> if (r.hasNext()) r.next().toMap() else null }
    }

    private fun deleteById(type: String, id: String) {
        db.transaction { db.command("sql", "DELETE FROM $type WHERE id = ?", id) }
    }

    private fun queryAll(type: String): List<Map<String, Any?>> {
        val rs = db.query("sql", "SELECT FROM $type")
        return rs.use { r -> generateSequence { if (r.hasNext()) r.next() else null }.map { it.toMap() }.toList() }
    }
}
