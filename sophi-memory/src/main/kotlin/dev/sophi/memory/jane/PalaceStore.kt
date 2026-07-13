package dev.sophi.memory.jane

import dev.sophi.memory.store.JsonlLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

@Serializable
private data class EmbeddingRecord(val id: String, val model: String, val v: String)

/**
 * All palace file I/O under [home]. Append-only JSONL event logs with last-record-wins
 * folds; [rewriteAll] is the compacting rewrite behind forget/purge (spec §5).
 * Malformed lines are skipped — a corrupt line must never take the palace down.
 */
class PalaceStore(private val home: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val memoriesLog = JsonlLog(home.resolve("memories.jsonl"))
    private val edgesLog = JsonlLog(home.resolve("edges.jsonl"))
    private val profileLog = JsonlLog(home.resolve("profile.jsonl"))
    private val embeddingsLog = JsonlLog(home.resolve("embeddings.jsonl"))
    private val recallsLog = JsonlLog(home.resolve("recalls.jsonl"))
    private val auditLog = JsonlLog(home.resolve("audit.jsonl"))

    private inline fun <reified T> decodeAll(log: JsonlLog): List<T> =
        log.readAll().mapNotNull { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    fun upsertMemory(m: Memory) = memoriesLog.append(json.encodeToString(Memory.serializer(), m))
    fun memories(): Map<String, Memory> = decodeAll<Memory>(memoriesLog).associateBy { it.id }

    fun upsertEdge(e: CausalEdge) = edgesLog.append(json.encodeToString(CausalEdge.serializer(), e))
    fun edges(): List<CausalEdge> =
        decodeAll<CausalEdge>(edgesLog).associateBy { it.key }.values.filter { !it.removed }

    fun upsertAttribute(a: ProfileAttribute) = profileLog.append(json.encodeToString(ProfileAttribute.serializer(), a))
    fun attributes(): Map<String, ProfileAttribute> =
        decodeAll<ProfileAttribute>(profileLog).associateBy { it.path }.filterValues { !it.deleted }

    fun putEmbedding(id: String, model: String, vector: FloatArray) =
        embeddingsLog.append(json.encodeToString(EmbeddingRecord.serializer(),
            EmbeddingRecord(id, model, encodeVector(vector))))
    fun embeddings(): Map<String, FloatArray> =
        decodeAll<EmbeddingRecord>(embeddingsLog).associate { it.id to decodeVector(it.v) }
    fun embeddingModel(): String? = decodeAll<EmbeddingRecord>(embeddingsLog).lastOrNull()?.model

    fun logRecall(r: RecallRecord) = recallsLog.append(json.encodeToString(RecallRecord.serializer(), r))
    fun recallsSince(ts: Long): List<RecallRecord> = decodeAll<RecallRecord>(recallsLog).filter { it.ts >= ts }

    fun appendAudit(line: String) = auditLog.append(line)

    fun writeLastRecall(text: String) {
        Files.createDirectories(home)
        Files.writeString(home.resolve("last-recall.txt"), text)
    }
    fun readLastRecall(): String? =
        home.resolve("last-recall.txt").takeIf { Files.exists(it) }?.let { Files.readString(it) }

    fun lastConsolidationMs(): Long? =
        home.resolve("consolidation.marker").takeIf { Files.exists(it) }
            ?.let { Files.readString(it).trim().toLongOrNull() }
    fun markConsolidation(nowMs: Long) {
        Files.createDirectories(home)
        Files.writeString(home.resolve("consolidation.marker"), nowMs.toString())
    }

    fun rewriteAll(
        memories: Collection<Memory>,
        edges: Collection<CausalEdge>,
        attributes: Collection<ProfileAttribute>,
        embeddings: Map<String, FloatArray>,
        embeddingModel: String?,
        recalls: List<RecallRecord>
    ) {
        rewrite("memories.jsonl", memories.map { json.encodeToString(Memory.serializer(), it) })
        rewrite("edges.jsonl", edges.map { json.encodeToString(CausalEdge.serializer(), it) })
        rewrite("profile.jsonl", attributes.map { json.encodeToString(ProfileAttribute.serializer(), it) })
        rewrite("embeddings.jsonl", embeddings.map { (id, v) ->
            json.encodeToString(EmbeddingRecord.serializer(), EmbeddingRecord(id, embeddingModel ?: "", encodeVector(v)))
        })
        rewrite("recalls.jsonl", recalls.map { json.encodeToString(RecallRecord.serializer(), it) })
    }

    fun wipe() {
        if (!Files.exists(home)) return
        Files.list(home).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
    }

    private fun rewrite(fileName: String, lines: List<String>) {
        Files.createDirectories(home)
        val tmp = home.resolve("$fileName.tmp")
        Files.write(tmp, lines.map { it.replace("\n", " ") })
        Files.move(tmp, home.resolve(fileName),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun encodeVector(v: FloatArray): String {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return Base64.getEncoder().encodeToString(buf.array())
    }

    private fun decodeVector(b64: String): FloatArray {
        val bytes = Base64.getDecoder().decode(b64)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }
}
