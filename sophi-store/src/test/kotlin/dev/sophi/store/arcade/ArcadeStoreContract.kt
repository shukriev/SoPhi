package dev.sophi.store.arcade

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/** Behaviour every [ArcadeStore] must share, whether the database is in this process or behind the host. */
abstract class ArcadeStoreContract(open: (Path) -> ArcadeStore) : FunSpec({

    fun store(): ArcadeStore = autoClose(open(tempdir().toPath())).also {
        it.ensureSchema(vertexTypes = listOf("Thing"), edgeTypes = listOf("LinksTo"), documentTypes = listOf("Note"))
    }

    test("vertex and document round-trips") {
        val s = store()
        s.upsertVertex("Thing", "t1", mapOf("name" to "Alice"))
        s.upsertVertex("Thing", "t1", mapOf("name" to "Alicia"))
        s.getVertex("Thing", "t1")?.get("name") shouldBe "Alicia"
        s.queryVertices("Thing").size shouldBe 1
        s.upsertDocument("Note", "n1", mapOf("text" to "hello"))
        s.documents("Note").single()["text"] shouldBe "hello"
        s.deleteDocument("Note", "n1")
        s.documents("Note") shouldBe emptyList()
    }

    test("edge upsert replaces the edge between the same pair") {
        val s = store()
        s.upsertVertex("Thing", "a", mapOf("name" to "A"))
        s.upsertVertex("Thing", "b", mapOf("name" to "B"))
        s.upsertEdge("LinksTo", "Thing", "a", "b", mapOf("label" to "x"))
        s.upsertEdge("LinksTo", "Thing", "a", "b", mapOf("label" to "y"))
        s.edges("LinksTo").single()["label"] shouldBe "y"
    }

    test("ensureSchema is idempotent") {
        val s = store()
        s.ensureSchema(vertexTypes = listOf("Thing"), edgeTypes = listOf("LinksTo"), documentTypes = listOf("Note"))
        s.upsertVertex("Thing", "t1", mapOf("name" to "A"))
        s.queryVertices("Thing").size shouldBe 1
    }

    test("query and command run raw SQL with positional params") {
        val s = store()
        s.command("INSERT INTO Note SET id = ?, text = ?", "n1", "raw")
        s.query("SELECT id, text FROM Note WHERE id = ?", "n1").single()["text"] shouldBe "raw"
    }

    test("fullText returns requested fields plus score, ordered, filtered by where") {
        val s = store()
        s.ensureSchema(documentTypes = listOf("Chunk"))
        s.ensureFullTextIndex("Chunk", "toks")
        s.upsertDocument("Chunk", "c1", mapOf("toks" to "compactor session window", "projectId" to "p1"))
        s.upsertDocument("Chunk", "c2", mapOf("toks" to "compactor only", "projectId" to "p1"))
        s.upsertDocument("Chunk", "c3", mapOf("toks" to "compactor session", "projectId" to "p2"))
        val rows = s.fullText("Chunk", "toks", "compactor session", fields = listOf("id"), limit = 10,
            where = "projectId = ?", params = listOf("p1"))
        rows.map { it["id"] } shouldBe listOf("c1", "c2")
        (rows.first()["score"] as Number).toDouble() shouldBe 2.0
    }

    test("vectors inserted with the record are found by nearestVectors, score = cosine distance") {
        val s = store()
        s.ensureVectorIndex("Thing", "emb", dimensions = 3)
        s.insertVertex("Thing", "a", mapOf("emb" to floatArrayOf(1f, 0f, 0f)))
        s.insertVertex("Thing", "b", mapOf("emb" to floatArrayOf(0f, 1f, 0f)))
        s.insertVertex("Thing", "c", mapOf("emb" to floatArrayOf(0.9f, 0.1f, 0f)))
        val nearest = s.nearestVectors("Thing", "emb", floatArrayOf(1f, 0f, 0f), 2)
        nearest.map { it.id } shouldBe listOf("a", "c")
        // Embedded reference semantics: score is the index's cosine distance (identical = 0.0, lower is closer).
        nearest.first().score shouldBe (0.0 plusOrMinus 0.01)
        (nearest[0].score < nearest[1].score) shouldBe true
    }

    test("ids survive a query round-trip") {
        val s = store()
        listOf("x", "y").forEach { s.insertVertex("Thing", it, mapOf("name" to it)) }
        s.query("SELECT id FROM Thing").map { it["id"] } shouldContainExactlyInAnyOrder listOf("x", "y")
    }
})

class EmbeddedArcadeStoreContractTest : ArcadeStoreContract({ EmbeddedArcadeStore.open(it) })

/** Remote store against a real loopback server; closing the store stops its server. */
class RemoteArcadeStoreContractTest : ArcadeStoreContract({ dir ->
    val srv = LocalArcadeServer.start(dir)
    srv.database("t")
    val remote = RemoteArcadeStore.connect(srv.port, "t", srv.password)
    object : ArcadeStore by remote {
        override fun close() { remote.close(); srv.close() }
    }
})

class RemoteEmbeddedAgreementTest : FunSpec({
    test("remote and embedded agree on cosine distance for the same index") {
        LocalArcadeServer.start(tempdir().toPath()).use { srv ->
            val embedded = EmbeddedArcadeStore(srv.database("t"))
            embedded.ensureSchema(vertexTypes = listOf("Thing"))
            embedded.ensureVectorIndex("Thing", "emb", dimensions = 3)
            embedded.insertVertex("Thing", "a", mapOf("emb" to floatArrayOf(1f, 0f, 0f)))
            embedded.insertVertex("Thing", "b", mapOf("emb" to floatArrayOf(0.6f, 0.8f, 0f)))
            embedded.insertVertex("Thing", "c", mapOf("emb" to floatArrayOf(0f, 0f, 1f)))
            RemoteArcadeStore.connect(srv.port, "t", srv.password).use { remote ->
                val q = floatArrayOf(1f, 0.2f, 0f)
                val e = embedded.nearestVectors("Thing", "emb", q, 3)
                val r = remote.nearestVectors("Thing", "emb", q, 3)
                r.map { it.id } shouldBe e.map { it.id }
                r.zip(e).forEach { (x, y) -> x.score shouldBe (y.score plusOrMinus 1e-3) }
            }
        }
    }
})
