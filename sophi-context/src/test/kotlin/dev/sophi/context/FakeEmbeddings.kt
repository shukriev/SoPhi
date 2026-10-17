package dev.sophi.context

import dev.sophi.ai.api.EmbeddingProvider
import dev.sophi.store.arcade.ArcadeStore
import dev.sophi.store.arcade.EmbeddedArcadeStore
import java.nio.file.Files
import java.nio.file.Path

/** Two "meanings": vehicles and fruit, so a query can match a file it shares no words with. */
class FakeEmbeddings(override val dimensions: Int = 4, private val fail: Boolean = false) : EmbeddingProvider {
    val seen = mutableListOf<String>()
    private val car = Regex("""\b(car|cars|automobile|vehicle)\b""", RegexOption.IGNORE_CASE)
    private val fruit = Regex("""\b(fruit|banana|apple)\b""", RegexOption.IGNORE_CASE)
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (fail) error("embeddings down")
        seen += texts
        return texts.map { t -> FloatArray(dimensions).also { v -> v[0] = if (car.containsMatchIn(t)) 1f else 0f; v[1] = if (fruit.containsMatchIn(t)) 1f else 0f; v[2] = 0.1f } }
    }
}

/** A fresh store with project "p" holding [files], scanned; with [embedder], every chunk is embedded too. Caller closes it. */
fun indexedStore(store: EmbeddedArcadeStore, root: Path, files: Map<String, String>, embedder: ChunkEmbedder? = null): ArcadeStore {
    ContextSchema.ensure(store)
    val ix = Indexer(store, GitProbe.none).also { it.rebuildIfStale(ContextEngine.SCHEMA_VERSION) }
    files.forEach { (p, t) -> root.resolve(p).also { Files.createDirectories(it.parent); Files.writeString(it, t) } }
    store.upsertDocument(ContextSchema.PROJECT, "p", mapOf("root" to root.toString(), "name" to "p", "status" to "active"))
    ix.scan("p")
    embedder?.let { e -> e.prepare(store); while (e.embedPending(store) > 0) Unit }
    return store
}
