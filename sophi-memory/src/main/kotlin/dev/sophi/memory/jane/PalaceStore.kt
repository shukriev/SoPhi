package dev.sophi.memory.jane

import dev.sophi.memory.store.JsonlLog
import dev.sophi.store.arcade.ArcadeStore
import dev.sophi.store.arcade.EmbeddedArcadeStore
import dev.sophi.store.arcade.Scored
import dev.sophi.store.arcade.vectorOf
import java.nio.file.Files
import java.nio.file.Path

private const val VEC = "MemoryVec"

/**
 * Memory/CausalEdge/ProfileAttribute/RecallRecord live in ArcadeDB (graph + document + vector);
 * audit/last-recall/consolidation-marker stay plain files (spec §4: they don't need query
 * capability, and an independent audit trail survives a corrupted database).
 *
 * Vectors live in `MemoryVec` (one per memory, inserted with the vector). The database is embedded at [home] or,
 * for the default home, `memory` on the shared server (ADR-041 M6, see [shared]). The plain files always stay in [home].
 */
class PalaceStore(
    private val home: Path,
    private val db: ArcadeStore = EmbeddedArcadeStore.open(home)
) {
    private val auditLog = JsonlLog(home.resolve("audit.jsonl"))
    /** Kept out of [auditLog]: that is the privacy trail for SENSITIVE+ accesses and must stay
     *  independently readable, not interleaved with write-gate telemetry. */
    private val encoderLog = JsonlLog(home.resolve("encoder.jsonl"))

    @Volatile private var vecIndexReady = false   // declared before init: it runs in textual order

    init {
        db.ensureSchema(
            vertexTypes = listOf("Memory", VEC),
            edgeTypes = listOf("CausalEdge"),
            documentTypes = listOf("ProfileAttribute", "RecallRecord")
        )
        adoptInlineVectors()
    }

    fun upsertMemory(m: Memory) = db.upsertVertex("Memory", m.id, m.toProperties())
    fun memories(): Map<String, Memory> = db.queryVertices("Memory").associate { it.memoryId() to it.toMemory() }
    fun deleteMemory(id: String) { db.deleteVertex(VEC, id); db.deleteVertex("Memory", id) }

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
        putVec(id, vector)
    }
    fun embeddings(): Map<String, FloatArray> =
        db.queryVertices(VEC).mapNotNull { v -> vectorOf(v["embedding"])?.let { v.memoryId() to it } }.toMap()
    fun embeddingModel(): String? = db.queryVertices("Memory").mapNotNull { it["embeddingModel"] as? String }.lastOrNull()
    fun vectorFor(id: String): FloatArray? = db.getVertex(VEC, id)?.let { vectorOf(it["embedding"]) }
    fun nearest(vector: FloatArray, k: Int): List<Scored> = db.nearestVectors(VEC, "embedding", vector, k)

    /** One MemoryVec per memory, inserted with its vector: an insert is indexed on the embedded and the remote
     *  store alike (an UPDATE isn't). Delete+insert in one transaction, so a replay after failover is harmless. */
    private fun putVec(id: String, vector: FloatArray) {
        if (!vecIndexReady) { db.ensureVectorIndex(VEC, "embedding", vector.size); vecIndexReady = true }
        db.transaction { db.deleteVertex(VEC, id); db.insertVertex(VEC, id, mapOf("embedding" to vector)) }
    }

    /**
     * Palaces written before M6 kept each vector on its Memory vertex: move it into a MemoryVec, once.
     * The inline copy is removed only once a MemoryVec for that memory reads back, so a crash at any point
     * loses nothing and the next open finishes the job. An existing MemoryVec wins: nothing writes inline
     * vectors any more, so it is the same vector or a newer one.
     */
    private fun adoptInlineVectors() {
        db.query("SELECT id, embedding FROM Memory WHERE embedding IS NOT NULL").forEach { row ->
            val id = row["id"] as String
            val inline = vectorOf(row["embedding"]) ?: return@forEach
            if (vectorFor(id) == null) putVec(id, inline)
            if (vectorFor(id) != null) db.command("UPDATE Memory REMOVE embedding WHERE id = ?", id)
        }
    }

    fun logRecall(r: RecallRecord) =
        db.upsertDocument("RecallRecord", "${r.ts}_${r.memoryId}_${r.sessionId}", r.toProperties())
    fun recallsSince(ts: Long): List<RecallRecord> =
        db.documents("RecallRecord").map { it.toRecallRecord() }.filter { it.ts >= ts }
    fun deleteRecallsFor(memoryId: String) =
        recallsSince(0L).filter { it.memoryId == memoryId }
            .forEach { db.deleteDocument("RecallRecord", "${it.ts}_${it.memoryId}_${it.sessionId}") }

    fun appendAudit(line: String) = auditLog.append(line)

    fun appendEncoderLog(line: String) = encoderLog.append(line)
    fun encoderLogLines(): List<String> = encoderLog.readAll()

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

    /** Releases the underlying ArcadeDB database — required before another process (or another
     *  PalaceStore in this process) can open the same [home] again (see class doc). */
    fun close() = db.close()

    fun wipe() {
        db.deleteAll(VEC); db.deleteAll("Memory"); db.deleteAll("CausalEdge")
        db.deleteAll("ProfileAttribute"); db.deleteAll("RecallRecord")
        Files.deleteIfExists(home.resolve("last-recall.txt"))
        Files.deleteIfExists(home.resolve("consolidation.marker"))
        Files.deleteIfExists(home.resolve("audit.jsonl"))
        Files.deleteIfExists(home.resolve("encoder.jsonl"))
    }

    private fun Map<String, Any?>.memoryId(): String = get("id") as String

    private fun Memory.toProperties(): Map<String, Any?> = mapOf(
        "text" to text, "room" to room.name, "salience" to salience,
        "sigRep" to signals.rep, "sigEmph" to signals.emph, "sigNov" to signals.nov,
        "sigAff" to signals.aff, "sigRec" to signals.rec, "sigDur" to signals.dur,
        "sensitivity" to sensitivity.name, "provenance" to provenance.name,
        "createdAt" to createdAt, "reinforcedAt" to reinforcedAt, "sourceSessionId" to sourceSessionId,
        "supersededBy" to supersededBy, "softDeletedAt" to softDeletedAt,
        "hitsPositive" to hitsPositive, "hitsNegative" to hitsNegative,
        "actionablePattern" to actionablePattern, "isCommitment" to isCommitment,
        "occurrences" to occurrences, "habitConfidence" to habitConfidence,
        "habitPreferredHour" to habitPreferredHour, "habitPreferredDayOfWeek" to habitPreferredDayOfWeek
    )
    private fun Map<String, Any?>.toMemory(): Memory = Memory(
        id = memoryId(), text = get("text") as String, room = Room.valueOf(get("room") as String),
        salience = (get("salience") as Number).toDouble(),
        signals = SalienceSignals(
            (get("sigRep") as Number).toDouble(), (get("sigEmph") as Number).toDouble(),
            (get("sigNov") as Number).toDouble(), (get("sigAff") as Number).toDouble(),
            (get("sigRec") as Number).toDouble(),
            dur = (get("sigDur") as Number?)?.toDouble() ?: 0.0
        ),
        sensitivity = Sensitivity.valueOf(get("sensitivity") as String),
        provenance = Provenance.valueOf(get("provenance") as String),
        createdAt = (get("createdAt") as Number).toLong(), reinforcedAt = (get("reinforcedAt") as Number).toLong(),
        sourceSessionId = get("sourceSessionId") as String,
        supersededBy = get("supersededBy") as String?,
        softDeletedAt = (get("softDeletedAt") as Number?)?.toLong(),
        hitsPositive = (get("hitsPositive") as? Number)?.toInt() ?: 0,
        hitsNegative = (get("hitsNegative") as? Number)?.toInt() ?: 0,
        actionablePattern = get("actionablePattern") as? Boolean ?: false,
        isCommitment = get("isCommitment") as? Boolean ?: false,
        occurrences = (get("occurrences") as? List<*>)?.map { (it as Number).toLong() }
            ?: listOf((get("createdAt") as Number).toLong()),
        habitConfidence = (get("habitConfidence") as? Number)?.toDouble() ?: 0.0,
        habitPreferredHour = (get("habitPreferredHour") as? Number)?.toInt(),
        habitPreferredDayOfWeek = (get("habitPreferredDayOfWeek") as? Number)?.toInt()
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
