package dev.sophi.memory.jane

import dev.sophi.memory.store.JsonlLog
import dev.sophi.memory.store.arcade.ArcadeStore
import dev.sophi.memory.store.arcade.EmbeddedArcadeStore
import dev.sophi.memory.store.arcade.Scored
import java.nio.file.Files
import java.nio.file.Path

/**
 * Memory/CausalEdge/ProfileAttribute/RecallRecord live in ArcadeDB (graph + document + vector);
 * audit/last-recall/consolidation-marker stay plain files (spec §4: they don't need query
 * capability, and an independent audit trail survives a corrupted database).
 *
 * Embedded-only: ArcadeDB locks its database directory to one process, so only one Sophi
 * process (CLI or sophi-web) should touch a given [home] at a time. A remote-client/server
 * split was considered (spec §2) but dropped — ArcadeDB 26.5.1's remote client has no path
 * to vector search (`vector.neighbors(...)` doesn't work in SQL, and the LSMVectorIndex Java
 * API it's replaced with here is embedded-only), so a remote client couldn't do the one thing
 * this migration is for. Revisit only if concurrent CLI+web usage becomes a real workflow.
 */
class PalaceStore(
    private val home: Path,
    private val db: ArcadeStore = EmbeddedArcadeStore.open(home)
) {
    private val auditLog = JsonlLog(home.resolve("audit.jsonl"))

    init {
        db.ensureSchema(
            vertexTypes = listOf("Memory"),
            edgeTypes = listOf("CausalEdge"),
            documentTypes = listOf("ProfileAttribute", "RecallRecord")
        )
    }

    fun upsertMemory(m: Memory) = db.upsertVertex("Memory", m.id, m.toProperties())
    fun memories(): Map<String, Memory> = db.queryVertices("Memory").associate { it.memoryId() to it.toMemory() }
    fun deleteMemory(id: String) = db.deleteVertex("Memory", id)

    fun upsertEdge(e: CausalEdge) = db.upsertEdge("CausalEdge", "Memory", e.fromId, e.toId, e.toProperties())
    fun edges(): List<CausalEdge> = db.edges("CausalEdge").map { it.toCausalEdge() }.filter { !it.removed }
    fun deleteEdge(fromId: String, toId: String) = db.deleteEdge("CausalEdge", fromId, toId)

    fun upsertAttribute(a: ProfileAttribute) = db.upsertDocument("ProfileAttribute", a.path, a.toProperties())
    fun attributes(): Map<String, ProfileAttribute> =
        db.documents("ProfileAttribute").associate { it["id"] as String to it.toProfileAttribute() }
            .filterValues { !it.deleted }
    fun deleteAttribute(path: String) = db.deleteDocument("ProfileAttribute", path)

    fun putEmbedding(id: String, model: String, vector: FloatArray) {
        db.upsertVertex("Memory", id, mapOf("embeddingModel" to model))
        db.putVector("Memory", id, "embedding", vector)
    }
    fun embeddings(): Map<String, FloatArray> =
        db.queryVertices("Memory").mapNotNull { v -> (v["embedding"] as? FloatArray)?.let { v.memoryId() to it } }.toMap()
    fun embeddingModel(): String? = db.queryVertices("Memory").mapNotNull { it["embeddingModel"] as? String }.lastOrNull()
    fun vectorFor(id: String): FloatArray? = db.getVertex("Memory", id)?.get("embedding") as? FloatArray
    fun nearest(vector: FloatArray, k: Int): List<Scored> = db.nearestVectors("Memory", "embedding", vector, k)

    fun logRecall(r: RecallRecord) =
        db.upsertDocument("RecallRecord", "${r.ts}_${r.memoryId}_${r.sessionId}", r.toProperties())
    fun recallsSince(ts: Long): List<RecallRecord> =
        db.documents("RecallRecord").map { it.toRecallRecord() }.filter { it.ts >= ts }
    fun deleteRecallsFor(memoryId: String) =
        recallsSince(0L).filter { it.memoryId == memoryId }
            .forEach { db.deleteDocument("RecallRecord", "${it.ts}_${it.memoryId}_${it.sessionId}") }

    fun appendAudit(line: String) = auditLog.append(line)

    fun writeLastRecall(text: String) {
        Files.createDirectories(home)
        Files.writeString(home.resolve("last-recall.txt"), text)
    }
    fun readLastRecall(): String? =
        home.resolve("last-recall.txt").takeIf { Files.exists(it) }?.let { Files.readString(it) }
    fun deleteLastRecall() = Files.deleteIfExists(home.resolve("last-recall.txt"))

    fun lastConsolidationMs(): Long? =
        home.resolve("consolidation.marker").takeIf { Files.exists(it) }
            ?.let { Files.readString(it).trim().toLongOrNull() }
    fun markConsolidation(nowMs: Long) {
        Files.createDirectories(home)
        Files.writeString(home.resolve("consolidation.marker"), nowMs.toString())
    }

    fun wipe() {
        db.deleteAll("Memory"); db.deleteAll("CausalEdge")
        db.deleteAll("ProfileAttribute"); db.deleteAll("RecallRecord")
        Files.deleteIfExists(home.resolve("last-recall.txt"))
        Files.deleteIfExists(home.resolve("consolidation.marker"))
        Files.deleteIfExists(home.resolve("audit.jsonl"))
    }

    private fun Map<String, Any?>.memoryId(): String = get("id") as String

    private fun Memory.toProperties(): Map<String, Any?> = mapOf(
        "text" to text, "room" to room.name, "salience" to salience,
        "sigRep" to signals.rep, "sigEmph" to signals.emph, "sigNov" to signals.nov,
        "sigAff" to signals.aff, "sigRec" to signals.rec,
        "sensitivity" to sensitivity.name, "provenance" to provenance.name,
        "createdAt" to createdAt, "reinforcedAt" to reinforcedAt, "sourceSessionId" to sourceSessionId,
        "supersededBy" to supersededBy, "softDeletedAt" to softDeletedAt
    )
    private fun Map<String, Any?>.toMemory(): Memory = Memory(
        id = memoryId(), text = get("text") as String, room = Room.valueOf(get("room") as String),
        salience = (get("salience") as Number).toDouble(),
        signals = SalienceSignals(
            (get("sigRep") as Number).toDouble(), (get("sigEmph") as Number).toDouble(),
            (get("sigNov") as Number).toDouble(), (get("sigAff") as Number).toDouble(),
            (get("sigRec") as Number).toDouble()
        ),
        sensitivity = Sensitivity.valueOf(get("sensitivity") as String),
        provenance = Provenance.valueOf(get("provenance") as String),
        createdAt = (get("createdAt") as Number).toLong(), reinforcedAt = (get("reinforcedAt") as Number).toLong(),
        sourceSessionId = get("sourceSessionId") as String,
        supersededBy = get("supersededBy") as String?,
        softDeletedAt = (get("softDeletedAt") as Number?)?.toLong()
    )

    private fun CausalEdge.toProperties(): Map<String, Any?> =
        mapOf("threadLabel" to threadLabel, "compressed" to compressed, "removed" to removed)
    private fun Map<String, Any?>.toCausalEdge(): CausalEdge = CausalEdge(
        fromId = get("fromId") as String, toId = get("toId") as String,
        threadLabel = get("threadLabel") as String,
        compressed = get("compressed") as? Boolean ?: false,
        removed = get("removed") as? Boolean ?: false
    )

    private fun ProfileAttribute.toProperties(): Map<String, Any?> = mapOf(
        "value" to value, "confidence" to confidence, "evidenceCount" to evidenceCount,
        "evidenceMemoryIds" to evidenceMemoryIds, "updatedAt" to updatedAt, "deleted" to deleted
    )
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toProfileAttribute(): ProfileAttribute = ProfileAttribute(
        path = get("id") as String, value = get("value") as String,
        confidence = (get("confidence") as Number).toDouble(),
        evidenceCount = (get("evidenceCount") as Number).toInt(),
        evidenceMemoryIds = get("evidenceMemoryIds") as? List<String> ?: emptyList(),
        updatedAt = (get("updatedAt") as Number).toLong(),
        deleted = get("deleted") as? Boolean ?: false
    )

    private fun RecallRecord.toProperties(): Map<String, Any?> =
        mapOf("ts" to ts, "memoryId" to memoryId, "sessionId" to sessionId)
    private fun Map<String, Any?>.toRecallRecord(): RecallRecord = RecallRecord(
        ts = (get("ts") as Number).toLong(), memoryId = get("memoryId") as String,
        sessionId = get("sessionId") as String
    )
}
