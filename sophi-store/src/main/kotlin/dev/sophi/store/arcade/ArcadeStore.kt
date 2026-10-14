package dev.sophi.store.arcade

data class Scored(val id: String, val score: Double)

/**
 * Generic document/graph/vector primitives over ArcadeDB. Domain-agnostic on purpose —
 * no Jane's-Palace or context-engine types here — so any consumer can build on this layer,
 * whether the database is open in this process or reached through the elected host (ADR-041).
 */
interface ArcadeStore : AutoCloseable {
    fun upsertVertex(type: String, id: String, properties: Map<String, Any?>)
    fun getVertex(type: String, id: String): Map<String, Any?>?
    fun queryVertices(type: String): List<Map<String, Any?>>
    fun deleteVertex(type: String, id: String)

    /** Creates a new vertex. Unlike [upsertVertex] this is a plain insert, so a vector property set
     *  here is picked up by an existing vector index without the [putVector] workaround. */
    fun insertVertex(type: String, id: String, properties: Map<String, Any?>)

    fun upsertEdge(type: String, vertexType: String, fromId: String, toId: String, properties: Map<String, Any?>)
    fun edges(type: String): List<Map<String, Any?>>
    fun deleteEdge(type: String, fromId: String, toId: String)

    /** Lazily declares the vector property + HNSW index for [vertexType].[property] on first use. */
    fun putVector(vertexType: String, id: String, property: String, vector: FloatArray)
    /** Nearest neighbours by cosine, closest first. Observed on the embedded store (26.5.1): [Scored.score] is the
     *  index's cosine distance (identical vector = 0.0, lower is closer), not a similarity.
     *  Callers must [ensureVectorIndex] first; behaviour without the index differs: embedded returns empty, remote throws. */
    fun nearestVectors(vertexType: String, property: String, query: FloatArray, k: Int): List<Scored>
    fun ensureVectorIndex(vertexType: String, property: String, dimensions: Int)

    fun upsertDocument(type: String, id: String, properties: Map<String, Any?>)
    fun documents(type: String): List<Map<String, Any?>>
    fun deleteDocument(type: String, id: String)

    fun ensureFullTextIndex(type: String, property: String)
    /**
     * Rows matching [query] in the FULL_TEXT index on [type].[property], restricted by an optional
     * SQL [where] (positional [params]), with [fields] plus `score`. ArcadeDB's score is the number of
     * matched query terms — good for picking candidates, not for final ranking.
     */
    fun fullText(
        type: String, property: String, query: String, fields: List<String>, limit: Int,
        where: String? = null, params: List<Any?> = emptyList()
    ): List<Map<String, Any?>>

    fun query(sql: String, vararg params: Any?): List<Map<String, Any?>>
    fun command(sql: String, vararg params: Any?)

    /**
     * Runs [block] in one transaction: operations inside join it, and a throw rolls all of them back.
     * Block runs on the calling thread; do not call the store from other threads inside it.
     * On the remote store the client lock is held for the whole block, so other callers wait.
     * ArcadeDB may retry the block on a concurrent-modification conflict, so it must be safe to run again.
     */
    fun <T> transaction(block: () -> T): T

    fun deleteAll(type: String)
    fun ensureSchema(vertexTypes: List<String> = emptyList(), edgeTypes: List<String> = emptyList(), documentTypes: List<String> = emptyList())
}
