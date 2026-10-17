package dev.sophi.context

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.ai.providers.buildOpenAiCompatEmbeddingProvider
import dev.sophi.store.arcade.ArcadeStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration

/**
 * Optional chunk embeddings (spec §5.2 step 6, ADR-041 §10): one ChunkVec per distinct chunkHash, inserted once with
 * its vector. Only the host calls [prepare]/[embedPending] (single writer); any process may call [queryVector].
 * [queryProvider] embeds queries; [create] gives it an HTTP timeout of [queryTimeoutMillis], because [withTimeout]
 * cannot interrupt a blocking HTTP send.
 */
class ChunkEmbedder(
    val provider: EmbeddingProvider,
    val model: String,
    private val batch: Int = 32,
    private val queryTimeoutMillis: Long = 2_000,
    private val queryProvider: EmbeddingProvider = provider
) {
    companion object {
        const val DEFAULT_URL = "http://localhost:1234/v1"
        private const val META_ID = "embed"
        private const val MAX_CHARS = 2_000
        private val V4_LOOPBACK = Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

        /** SOPHI_CONTEXT_EMBED=<model> (unset or blank = off), SOPHI_CONTEXT_EMBED_URL (default [DEFAULT_URL]), SOPHI_CONTEXT_EMBED_DIMS (768). */
        fun fromKnobs(env: Map<String, String>, log: (String) -> Unit = { System.err.println(it) }): ChunkEmbedder? {
            val model = env["SOPHI_CONTEXT_EMBED"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val dims = env["SOPHI_CONTEXT_EMBED_DIMS"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 768
            return create(env["SOPHI_CONTEXT_EMBED_URL"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_URL, model, dims, log = log)
        }

        /** Chunk text must not leave the machine in M5 (spec §5.4): a non-loopback [baseUrl] is refused. */
        fun create(baseUrl: String, model: String, dims: Int, queryTimeoutMillis: Long = 2_000,
                   log: (String) -> Unit = { System.err.println(it) }): ChunkEmbedder? {
            if (!isLoopback(baseUrl)) {
                log("context: embeddings off — $baseUrl is not a loopback address (only a local embedding server may see file contents)")
                return null
            }
            return ChunkEmbedder(buildOpenAiCompatEmbeddingProvider(baseUrl, null, model, dims), model, queryTimeoutMillis = queryTimeoutMillis,
                queryProvider = buildOpenAiCompatEmbeddingProvider(baseUrl, null, model, dims, Duration.ofMillis(queryTimeoutMillis)))
        }

        internal fun isLoopback(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.removeSurrounding("[", "]")?.lowercase() ?: return false
            return host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1" || V4_LOOPBACK.matches(host)
        }
    }

    fun prepare(store: ArcadeStore) {
        val dims = provider.dimensions
        val meta = store.query("SELECT model, dims FROM ${ContextSchema.META} WHERE id = ?", META_ID).firstOrNull()
        if (meta != null && (meta["model"] != model || (meta["dims"] as Number?)?.toInt() != dims)) {
            // spec §8: model/dims change → drop vectors, re-embed lazily; lexical meanwhile. The index's dims are fixed, so drop the type.
            store.command("DROP TYPE ${ContextSchema.CHUNKVEC} UNSAFE")
            store.ensureSchema(vertexTypes = listOf(ContextSchema.CHUNKVEC))
            store.command("UPDATE ${ContextSchema.CHUNK} SET vec = null")
        }
        store.ensureVectorIndex(ContextSchema.CHUNKVEC, "emb", dims)
        store.upsertDocument(ContextSchema.META, META_ID, mapOf("model" to model, "dims" to dims))
    }

    /** Embeds up to [max] chunks still without a vector, docs/instructions first; 0 = nothing left. [cancelled] is checked between batches. */
    fun embedPending(store: ArcadeStore, max: Int = 64, cancelled: () -> Boolean = { false }): Int {
        val fields = "chunkHash, path, label, text"
        val rows = store.query("SELECT $fields FROM ${ContextSchema.CHUNK} WHERE vec IS NULL AND role IN ? LIMIT $max", listOf("DOC", "INSTRUCTION"))
            .ifEmpty { store.query("SELECT $fields FROM ${ContextSchema.CHUNK} WHERE vec IS NULL LIMIT $max") }
        if (rows.isEmpty()) return 0
        val byHash = rows.associateBy { it["chunkHash"] as String }
        val have = store.query("SELECT id FROM ${ContextSchema.CHUNKVEC} WHERE id IN ?", byHash.keys.toList()).mapTo(HashSet()) { it["id"] as String }
        val done = ArrayList<String>(have.filter { it in byHash })
        for (part in byHash.filterKeys { it !in have }.entries.chunked(batch)) {
            if (cancelled()) break
            val vectors = runBlocking { provider.embed(part.map { (_, r) -> listOfNotNull(r["path"], r["label"], r["text"]).joinToString("\n").take(MAX_CHARS) }) }
            store.transaction { part.forEachIndexed { i, (hash, _) -> store.insertVertex(ContextSchema.CHUNKVEC, hash, mapOf("emb" to vectors[i])) } }
            part.mapTo(done) { it.key }
        }
        done.forEach { store.command("UPDATE ${ContextSchema.CHUNK} SET vec = true WHERE chunkHash = ?", it) }
        return done.size
    }

    /** null on any failure or timeout: the caller searches lexically. */
    fun queryVector(text: String): FloatArray? = try {
        runBlocking { withTimeout(queryTimeoutMillis) { queryProvider.embed(listOf(text)).single() } }
    } catch (e: InterruptedException) { Thread.currentThread().interrupt(); null } catch (e: Exception) { null }
}
