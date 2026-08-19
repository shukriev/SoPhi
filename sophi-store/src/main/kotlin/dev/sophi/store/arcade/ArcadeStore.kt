package dev.sophi.store.arcade

data class Scored(val id: String, val score: Double)

/**
 * Generic document/graph/vector primitives over ArcadeDB. Domain-agnostic on purpose —
 * no Jane's-Palace types here — so a future second memory technique can build on this
 * same layer (spec: docs/superpowers/specs/2026-08-18-arcadedb-memory-storage-design.md).
 */
interface ArcadeStore : AutoCloseable {
    fun upsertVertex(type: String, id: String, properties: Map<String, Any?>)
    fun getVertex(type: String, id: String): Map<String, Any?>?
    fun queryVertices(type: String): List<Map<String, Any?>>
    fun deleteVertex(type: String, id: String)

    fun upsertEdge(type: String, vertexType: String, fromId: String, toId: String, properties: Map<String, Any?>)
    fun edges(type: String): List<Map<String, Any?>>
    fun deleteEdge(type: String, fromId: String, toId: String)

    /** Lazily declares the vector property + HNSW index for [vertexType].[property] on first use. */
    fun putVector(vertexType: String, id: String, property: String, vector: FloatArray)
    fun nearestVectors(vertexType: String, property: String, query: FloatArray, k: Int): List<Scored>

    fun upsertDocument(type: String, id: String, properties: Map<String, Any?>)
    fun documents(type: String): List<Map<String, Any?>>
    fun deleteDocument(type: String, id: String)

    fun deleteAll(type: String)
    fun ensureSchema(vertexTypes: List<String> = emptyList(), edgeTypes: List<String> = emptyList(), documentTypes: List<String> = emptyList())
}
