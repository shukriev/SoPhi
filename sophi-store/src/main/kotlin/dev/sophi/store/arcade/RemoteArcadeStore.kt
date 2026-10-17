package dev.sophi.store.arcade

import com.arcadedb.remote.RemoteDatabase

/**
 * The store as seen by a process that isn't the elected host: the same SQL, over ArcadeDB's HTTP
 * API on loopback. Vector search uses SQL `vectorNeighbors` (the bare `vector.neighbors` spelling
 * fails to parse — ADR-026 addendum). Vector writes are host-only: the index `put` workaround in
 * [EmbeddedArcadeStore.putVector] needs the embedded Java API.
 */
class RemoteArcadeStore(private val remote: RemoteDatabase) : SqlArcadeStore(remote) {

    companion object {
        fun connect(port: Int, database: String, password: String): RemoteArcadeStore =
            RemoteArcadeStore(RemoteDatabase("127.0.0.1", port, database, LocalArcadeServer.USER, password))
    }

    /** A FloatArray crosses the wire as its toString ("[F@…"); a list of floats arrives intact. */
    override fun param(value: Any?): Any? = if (value is FloatArray) value.toList() else value

    override fun putVector(vertexType: String, id: String, property: String, vector: FloatArray) =
        throw UnsupportedOperationException("vector writes run on the elected host; use insertVertex or the host's store")

    // RemoteDatabase keeps its transaction state in one shared sessionId, so concurrent calls corrupt each other.
    // ponytail: one lock per client; pool a RemoteDatabase per thread if remote write throughput ever matters
    override fun query(sql: String, vararg params: Any?): List<Map<String, Any?>> = synchronized(remote) { super.query(sql, *params) }
    override fun command(sql: String, vararg params: Any?) = synchronized(remote) { super.command(sql, *params) }
    override fun upsertEdge(type: String, vertexType: String, fromId: String, toId: String, properties: Map<String, Any?>) =
        synchronized(remote) { super.upsertEdge(type, vertexType, fromId, toId, properties) }

    override fun nearestVectors(vertexType: String, property: String, query: FloatArray, k: Int): List<Scored> = synchronized(remote) {
        val index = "$vertexType[$property]"
        // Same as embedded: no index yet (nothing embedded so far) means no neighbours, not an error.
        if (this.query("SELECT name FROM schema:indexes").none { it["name"] == index }) return@synchronized emptyList()
        // `this.` because the `query` parameter (the vector) shadows the method.
        this.query("SELECT expand(vectorNeighbors('$index', ?, $k))", query).map { row ->
            // The contract is cosine distance (lower = closer), same as EmbeddedArcadeStore: pass SQL's value through.
            Scored(row["id"] as String, (row["distance"] as Number).toDouble())
        }
    }

    override fun <T> transaction(block: () -> T): T = synchronized(remote) { super.transaction(block) }

    override fun close() = remote.close()
}
