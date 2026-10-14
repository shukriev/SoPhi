package dev.sophi.context

import dev.sophi.store.arcade.ArcadeStore

data class Meta(val schemaVersion: Int, val tokenizerVersion: Int, val chunkCount: Long, val totalLen: Long)

/** Document types of the `context` database (spec §5.1; vertices/edges arrive with M5). */
object ContextSchema {
    const val PROJECT = "Project"
    const val FILE = "File"
    const val CHUNK = "Chunk"
    const val TERM = "TermStat"
    const val META = "ContextMeta"
    const val REQUEST = "IndexRequest"
    private const val META_ID = "meta"

    fun ensure(store: ArcadeStore) {
        store.ensureSchema(documentTypes = listOf(PROJECT, FILE, CHUNK, TERM, META, REQUEST))
        store.ensureFullTextIndex(CHUNK, "toks")
        listOf(CHUNK to "fileId", CHUNK to "projectId", FILE to "projectId").forEach { (t, p) ->
            store.command("CREATE PROPERTY $t.$p IF NOT EXISTS STRING")
            store.command("CREATE INDEX IF NOT EXISTS ON $t ($p) NOTUNIQUE")
        }
    }

    fun fileId(projectId: String, rel: String) = "$projectId:$rel"
    fun chunkId(fileId: String, ordinal: Int) = "$fileId#$ordinal"

    fun meta(store: ArcadeStore): Meta? = store.query("SELECT FROM $META WHERE id = ?", META_ID).firstOrNull()?.let {
        Meta((it["schemaVersion"] as Number).toInt(), (it["tokenizerVersion"] as Number).toInt(),
            (it["chunkCount"] as Number).toLong(), (it["totalLen"] as Number).toLong())
    }

    fun putMeta(store: ArcadeStore, m: Meta) = store.upsertDocument(META, META_ID, mapOf(
        "schemaVersion" to m.schemaVersion, "tokenizerVersion" to m.tokenizerVersion,
        "chunkCount" to m.chunkCount, "totalLen" to m.totalLen))
}
