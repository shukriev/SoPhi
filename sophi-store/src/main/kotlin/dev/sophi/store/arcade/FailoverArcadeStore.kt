package dev.sophi.store.arcade

import java.util.concurrent.atomic.AtomicBoolean

/**
 * [database] on the shared host as a plain [ArcadeStore], so callers (PalaceStore, VersionStore) need not know
 * about election: every call goes through [ArcadeHost.withStore] and is retried once on a new host.
 * Calls inside [transaction] use that transaction's store; the whole block is what gets retried.
 * Every write a caller makes must therefore be safe to repeat (upsert, delete, or delete+insert in one transaction).
 */
class FailoverArcadeStore(
    private val lease: SharedArcadeHost.Lease,
    private val database: String,
    schemaVersion: Int
) : ArcadeStore {
    init { lease.host.register(database, schemaVersion) }

    private val tx = ThreadLocal<ArcadeStore?>()
    private val closed = AtomicBoolean(false)

    val role: Role get() = lease.host.role

    private fun <T> on(op: (ArcadeStore) -> T): T {
        val inTx = tx.get()
        return if (inTx != null) op(inTx) else lease.host.withStore(database, op)
    }

    override fun upsertVertex(type: String, id: String, properties: Map<String, Any?>) = on { it.upsertVertex(type, id, properties) }
    override fun getVertex(type: String, id: String): Map<String, Any?>? = on { it.getVertex(type, id) }
    override fun queryVertices(type: String): List<Map<String, Any?>> = on { it.queryVertices(type) }
    override fun deleteVertex(type: String, id: String) = on { it.deleteVertex(type, id) }
    override fun insertVertex(type: String, id: String, properties: Map<String, Any?>) = on { it.insertVertex(type, id, properties) }
    override fun upsertEdge(type: String, vertexType: String, fromId: String, toId: String, properties: Map<String, Any?>) =
        on { it.upsertEdge(type, vertexType, fromId, toId, properties) }
    override fun edges(type: String): List<Map<String, Any?>> = on { it.edges(type) }
    override fun deleteEdge(type: String, fromId: String, toId: String) = on { it.deleteEdge(type, fromId, toId) }
    override fun putVector(vertexType: String, id: String, property: String, vector: FloatArray) = on { it.putVector(vertexType, id, property, vector) }
    override fun nearestVectors(vertexType: String, property: String, query: FloatArray, k: Int): List<Scored> =
        on { it.nearestVectors(vertexType, property, query, k) }
    override fun ensureVectorIndex(vertexType: String, property: String, dimensions: Int) = on { it.ensureVectorIndex(vertexType, property, dimensions) }
    override fun upsertDocument(type: String, id: String, properties: Map<String, Any?>) = on { it.upsertDocument(type, id, properties) }
    override fun documents(type: String): List<Map<String, Any?>> = on { it.documents(type) }
    override fun deleteDocument(type: String, id: String) = on { it.deleteDocument(type, id) }
    override fun ensureFullTextIndex(type: String, property: String) = on { it.ensureFullTextIndex(type, property) }
    override fun fullText(type: String, property: String, query: String, fields: List<String>, limit: Int, where: String?, params: List<Any?>) =
        on { it.fullText(type, property, query, fields, limit, where, params) }
    override fun query(sql: String, vararg params: Any?): List<Map<String, Any?>> = on { it.query(sql, *params) }
    override fun command(sql: String, vararg params: Any?) = on { it.command(sql, *params) }
    override fun deleteAll(type: String) = on { it.deleteAll(type) }
    override fun ensureSchema(vertexTypes: List<String>, edgeTypes: List<String>, documentTypes: List<String>) =
        on { it.ensureSchema(vertexTypes, edgeTypes, documentTypes) }

    override fun <T> transaction(block: () -> T): T {
        val inTx = tx.get()
        if (inTx != null) return inTx.transaction(block)
        return lease.host.withStore(database) { s ->
            tx.set(s)
            try { s.transaction(block) } finally { tx.remove() }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { lease.host.unregister(database) }
            lease.close()
        }
    }
}
