package dev.sophi.store.arcade

import com.arcadedb.database.BasicDatabase
import com.arcadedb.query.sql.executor.ResultSet

/**
 * Everything expressible in ArcadeDB SQL, written once for both an in-process database and a
 * RemoteDatabase reached through the elected host. Subclasses override only what needs the
 * embedded Java API ([EmbeddedArcadeStore]) or a wire-format tweak ([RemoteArcadeStore]).
 *
 * Type, property and field names and `where` fragments are interpolated into the SQL text: they must be
 * code-supplied constants, never user input. Values always go through `?` parameters.
 */
abstract class SqlArcadeStore(protected val db: BasicDatabase) : ArcadeStore {

    /** Hook for values the remote wire format can't carry as-is (FloatArray). */
    protected open fun param(value: Any?): Any? = value

    private fun args(params: Array<out Any?>): Array<Any?> = params.map { param(it) }.toTypedArray()

    protected fun rows(rs: ResultSet): List<Map<String, Any?>> =
        rs.use { r -> generateSequence { if (r.hasNext()) r.next() else null }.map { it.toMap() }.toList() }

    override fun query(sql: String, vararg params: Any?): List<Map<String, Any?>> = rows(db.query("sql", sql, *args(params)))

    // Embedded writes need an active transaction (it joins an enclosing one); on a remote database the wrapper is a plain client-side transaction (it ran DDL and nested use fine).
    override fun command(sql: String, vararg params: Any?) { db.transaction { db.command("sql", sql, *args(params)).close() } }

    override fun <T> transaction(block: () -> T): T {
        var result: Any? = null
        db.transaction { result = block() }   // a throw inside rolls back and propagates
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    override fun ensureSchema(vertexTypes: List<String>, edgeTypes: List<String>, documentTypes: List<String>) {
        vertexTypes.forEach { command("CREATE VERTEX TYPE $it IF NOT EXISTS") }
        edgeTypes.forEach { command("CREATE EDGE TYPE $it IF NOT EXISTS") }
        documentTypes.forEach { command("CREATE DOCUMENT TYPE $it IF NOT EXISTS") }
        // UPSERT (used by upsertRecord) requires an index on the WHERE-matched property.
        (vertexTypes + documentTypes).forEach {
            command("CREATE PROPERTY $it.id IF NOT EXISTS STRING")
            command("CREATE INDEX IF NOT EXISTS ON $it (id) UNIQUE")
        }
    }

    override fun upsertVertex(type: String, id: String, properties: Map<String, Any?>) = upsertRecord(type, id, properties)
    override fun getVertex(type: String, id: String): Map<String, Any?>? = query("SELECT FROM $type WHERE id = ?", id).firstOrNull()
    override fun queryVertices(type: String): List<Map<String, Any?>> = query("SELECT FROM $type")
    override fun deleteVertex(type: String, id: String) = command("DELETE FROM $type WHERE id = ?", id)

    override fun insertVertex(type: String, id: String, properties: Map<String, Any?>) {
        val all = mapOf("id" to id) + properties
        command("CREATE VERTEX $type SET " + all.keys.joinToString(", ") { "$it = ?" }, *all.values.toTypedArray())
    }

    override fun upsertEdge(type: String, vertexType: String, fromId: String, toId: String, properties: Map<String, Any?>) {
        val all = mapOf("fromId" to fromId, "toId" to toId) + properties
        val setClause = all.keys.joinToString(", ") { "$it = ?" }
        db.transaction {
            command("DELETE FROM $type WHERE fromId = ? AND toId = ?", fromId, toId)
            command(
                "CREATE EDGE $type FROM (SELECT FROM $vertexType WHERE id = ?) TO (SELECT FROM $vertexType WHERE id = ?) SET $setClause",
                fromId, toId, *all.values.toTypedArray()
            )
        }
    }

    override fun edges(type: String): List<Map<String, Any?>> = query("SELECT FROM $type")
    override fun deleteEdge(type: String, fromId: String, toId: String) =
        command("DELETE FROM $type WHERE fromId = ? AND toId = ?", fromId, toId)

    override fun ensureVectorIndex(vertexType: String, property: String, dimensions: Int) {
        command("CREATE PROPERTY $vertexType.$property IF NOT EXISTS ARRAY_OF_FLOATS")
        command("CREATE INDEX IF NOT EXISTS ON $vertexType ($property) LSM_VECTOR METADATA { dimensions: $dimensions, similarity: 'COSINE' }")
    }

    override fun upsertDocument(type: String, id: String, properties: Map<String, Any?>) = upsertRecord(type, id, properties)
    override fun documents(type: String): List<Map<String, Any?>> = query("SELECT FROM $type")
    override fun deleteDocument(type: String, id: String) = command("DELETE FROM $type WHERE id = ?", id)

    override fun ensureFullTextIndex(type: String, property: String) {
        command("CREATE PROPERTY $type.$property IF NOT EXISTS STRING")
        command("CREATE INDEX IF NOT EXISTS ON $type ($property) FULL_TEXT")
    }

    override fun fullText(
        type: String, property: String, query: String, fields: List<String>, limit: Int,
        where: String?, params: List<Any?>
    ): List<Map<String, Any?>> {
        val filter = where?.let { " AND ($it)" } ?: ""
        val projection = (fields + "\$score AS score").joinToString(", ")
        // `this.` because the `query` parameter shadows the method.
        return this.query(
            "SELECT $projection FROM $type WHERE SEARCH_INDEX('$type[$property]', ?) = true$filter ORDER BY \$score DESC LIMIT $limit",
            query, *params.toTypedArray()
        )
    }

    override fun deleteAll(type: String) = command("DELETE FROM $type")

    private fun upsertRecord(type: String, id: String, properties: Map<String, Any?>) {
        val all = mapOf("id" to id) + properties
        val setClause = all.keys.joinToString(", ") { "$it = ?" }
        command("UPDATE $type SET $setClause UPSERT WHERE id = ?", *all.values.toTypedArray(), id)
    }
}
