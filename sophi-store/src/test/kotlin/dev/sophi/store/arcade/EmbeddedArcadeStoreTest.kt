package dev.sophi.store.arcade

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class EmbeddedArcadeStoreTest : FunSpec({
    fun store(): ArcadeStore {
        val s = EmbeddedArcadeStore.open(tempdir().toPath())
        s.ensureSchema(vertexTypes = listOf("Thing"), edgeTypes = listOf("LinksTo"), documentTypes = listOf("Note"))
        return s
    }

    test("vertex upsert, get, query, delete round-trip") {
        val s = store()
        s.upsertVertex("Thing", "t1", mapOf("name" to "Alice", "count" to 3))
        s.getVertex("Thing", "t1")?.get("name") shouldBe "Alice"
        s.queryVertices("Thing").map { it["id"] } shouldContainExactlyInAnyOrder listOf("t1")
        s.deleteVertex("Thing", "t1")
        s.getVertex("Thing", "t1") shouldBe null
    }

    test("vertex upsert overwrites by id, not duplicates") {
        val s = store()
        s.upsertVertex("Thing", "t1", mapOf("name" to "Alice"))
        s.upsertVertex("Thing", "t1", mapOf("name" to "Alicia"))
        s.queryVertices("Thing").size shouldBe 1
        s.getVertex("Thing", "t1")?.get("name") shouldBe "Alicia"
    }

    test("edge upsert, query, delete round-trip") {
        val s = store()
        s.upsertVertex("Thing", "a", mapOf("name" to "A"))
        s.upsertVertex("Thing", "b", mapOf("name" to "B"))
        s.upsertEdge("LinksTo", "Thing", "a", "b", mapOf("label" to "x"))
        s.edges("LinksTo").single().let { it["fromId"] shouldBe "a"; it["toId"] shouldBe "b" }
        s.deleteEdge("LinksTo", "a", "b")
        s.edges("LinksTo") shouldBe emptyList()
    }

    test("edge upsert overwrites the existing edge between the same pair") {
        val s = store()
        s.upsertVertex("Thing", "a", mapOf("name" to "A"))
        s.upsertVertex("Thing", "b", mapOf("name" to "B"))
        s.upsertEdge("LinksTo", "Thing", "a", "b", mapOf("label" to "x"))
        s.upsertEdge("LinksTo", "Thing", "a", "b", mapOf("label" to "y"))
        s.edges("LinksTo").size shouldBe 1
        s.edges("LinksTo").single()["label"] shouldBe "y"
    }

    test("document upsert, query, delete round-trip") {
        val s = store()
        s.upsertDocument("Note", "n1", mapOf("text" to "hello"))
        s.documents("Note").single()["text"] shouldBe "hello"
        s.deleteDocument("Note", "n1")
        s.documents("Note") shouldBe emptyList()
    }

    test("vector put and nearest-neighbor ordering") {
        val s = store()
        s.upsertVertex("Thing", "a", mapOf("name" to "A"))
        s.upsertVertex("Thing", "b", mapOf("name" to "B"))
        s.upsertVertex("Thing", "c", mapOf("name" to "C"))
        s.putVector("Thing", "a", "vec", floatArrayOf(1f, 0f, 0f))
        s.putVector("Thing", "b", "vec", floatArrayOf(0f, 1f, 0f))
        s.putVector("Thing", "c", "vec", floatArrayOf(0.9f, 0.1f, 0f))
        val nearest = s.nearestVectors("Thing", "vec", floatArrayOf(1f, 0f, 0f), 2)
        nearest.map { it.id } shouldBe listOf("a", "c")
    }

    test("deleteAll clears every record of a type") {
        val s = store()
        s.upsertVertex("Thing", "a", mapOf("name" to "A"))
        s.upsertVertex("Thing", "b", mapOf("name" to "B"))
        s.deleteAll("Thing")
        s.queryVertices("Thing") shouldBe emptyList()
    }
})
