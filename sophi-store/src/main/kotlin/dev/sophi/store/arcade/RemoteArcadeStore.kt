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

    override fun nearestVectors(vertexType: String, property: String, query: FloatArray, k: Int): List<Scored> =
        // `this.` because the `query` parameter (the vector) shadows the method.
        this.query("SELECT expand(vectorNeighbors('$vertexType[$property]', ?, $k))", query).map { row ->
            // The contract is cosine distance (lower = closer), same as EmbeddedArcadeStore: pass SQL's value through.
            Scored(row["id"] as String, (row["distance"] as Number).toDouble())
        }

    override fun close() = remote.close()
}
