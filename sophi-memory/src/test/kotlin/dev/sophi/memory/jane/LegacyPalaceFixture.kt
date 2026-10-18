package dev.sophi.memory.jane

import dev.sophi.store.arcade.EmbeddedArcadeStore
import java.nio.file.Path

/** A palace as written before M6: embedded at home/arcadedb, each vector on its Memory vertex via putVector. */
object LegacyPalaceFixture {
    fun write(home: Path, memories: List<Pair<String, String>>, vectors: Map<String, FloatArray> = emptyMap(), edge: Pair<String, String>? = null) {
        EmbeddedArcadeStore.open(home).use { db ->
            db.ensureSchema(vertexTypes = listOf("Memory"), edgeTypes = listOf("CausalEdge"), documentTypes = listOf("ProfileAttribute", "RecallRecord"))
            memories.forEach { (id, text) ->
                db.upsertVertex("Memory", id, mapOf(
                    "text" to text, "room" to "EPISODES", "salience" to 0.5,
                    "sigRep" to 0.0, "sigEmph" to 0.0, "sigNov" to 0.0, "sigAff" to 0.0, "sigRec" to 1.0,
                    "sensitivity" to "PERSONAL", "provenance" to "USER_DIRECT",
                    "createdAt" to 1L, "reinforcedAt" to 1L, "sourceSessionId" to "s"
                ))
            }
            vectors.forEach { (id, v) -> db.upsertVertex("Memory", id, mapOf("embeddingModel" to "fake")); db.putVector("Memory", id, "embedding", v) }
            edge?.let { (f, t) -> db.upsertEdge("CausalEdge", "Memory", f, t, mapOf("threadLabel" to "t", "compressed" to false, "removed" to false)) }
            db.upsertDocument("RecallRecord", "1_${memories.first().first}_s", mapOf("ts" to 1L, "memoryId" to memories.first().first, "sessionId" to "s"))
        }
    }
}
