package dev.sophi.memory.jane

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

class PalaceStoreTest : FunSpec({
    fun store() = PalaceStore(tempdir().toPath())
    fun mem(id: String, room: Room = Room.EPISODES) = Memory(
        id, "text-$id", room, 0.5, SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
        Sensitivity.PERSONAL, Provenance.USER_DIRECT, 1L, 1L, "s"
    )

    test("memory fold: last record per id wins") {
        val s = store()
        s.upsertMemory(mem("mem_1"))
        s.upsertMemory(mem("mem_1").copy(salience = 0.9))
        s.memories().getValue("mem_1").salience shouldBe 0.9
    }

    test("edge fold: last record per (from,to) wins and removed edges disappear") {
        val s = store()
        s.upsertMemory(mem("a")); s.upsertMemory(mem("b")); s.upsertMemory(mem("c"))
        s.upsertEdge(CausalEdge("a", "b", "t"))
        s.upsertEdge(CausalEdge("a", "b", "t", removed = true))
        s.upsertEdge(CausalEdge("b", "c", "t"))
        s.edges().map { it.key } shouldBe listOf("b->c")
    }

    test("attributes fold by path; deleted filtered") {
        val s = store()
        s.upsertAttribute(ProfileAttribute("a.b", "x", 0.5, 1, listOf("mem_1"), 1L))
        s.upsertAttribute(ProfileAttribute("a.b", "y", 0.8, 2, listOf("mem_1", "mem_2"), 2L))
        s.upsertAttribute(ProfileAttribute("c.d", "z", 0.5, 1, listOf("mem_3"), 1L, deleted = true))
        s.attributes().keys shouldBe setOf("a.b")
        s.attributes().getValue("a.b").value shouldBe "y"
    }

    test("embeddings round-trip float32 exactly and record the model") {
        val s = store()
        val v = floatArrayOf(0.25f, -1.5f, 3.14159f)
        s.putEmbedding("mem_1", "test-model", v)
        s.embeddings().getValue("mem_1").toList() shouldBe v.toList()
        s.embeddingModel() shouldBe "test-model"
    }

    test("recallsSince filters by timestamp") {
        val s = store()
        s.logRecall(RecallRecord(100L, "mem_1", "s1"))
        s.logRecall(RecallRecord(200L, "mem_2", "s1"))
        s.recallsSince(150L).map { it.memoryId } shouldBe listOf("mem_2")
    }

    test("consolidation marker round-trips; absent initially") {
        val s = store()
        s.lastConsolidationMs() shouldBe null
        s.markConsolidation(42L)
        s.lastConsolidationMs() shouldBe 42L
    }

    test("deleteMemory removes it from memories()") {
        val s = store()
        s.upsertMemory(mem("mem_1")); s.upsertMemory(mem("mem_2"))
        s.deleteMemory("mem_1")
        s.memories().keys shouldBe setOf("mem_2")
    }

    test("deleteEdge removes it from edges()") {
        val s = store()
        s.upsertMemory(mem("mem_1")); s.upsertMemory(mem("mem_2"))
        s.upsertEdge(CausalEdge("mem_1", "mem_2", "t"))
        s.deleteEdge("mem_1", "mem_2")
        s.edges() shouldBe emptyList()
    }

    test("deleteAttribute removes it from attributes()") {
        val s = store()
        s.upsertAttribute(ProfileAttribute("a.b", "x", 0.5, 1, listOf("mem_1"), 1L))
        s.deleteAttribute("a.b")
        s.attributes().keys shouldBe emptySet()
    }

    test("vectorFor and nearest round-trip") {
        val s = store()
        s.upsertMemory(mem("mem_1")); s.upsertMemory(mem("mem_2"))
        s.putEmbedding("mem_1", "m", floatArrayOf(1f, 0f))
        s.putEmbedding("mem_2", "m", floatArrayOf(0f, 1f))
        s.vectorFor("mem_1")?.toList() shouldBe listOf(1f, 0f)
        s.nearest(floatArrayOf(1f, 0f), 1).map { it.id } shouldBe listOf("mem_1")
    }

    test("wipe clears memories, edges, attributes, embeddings, and last-recall state") {
        val s = store()
        s.upsertMemory(mem("mem_1"))
        s.upsertEdge(CausalEdge("mem_1", "mem_1", "t"))
        s.upsertAttribute(ProfileAttribute("a.b", "x", 0.5, 1, listOf("mem_1"), 1L))
        s.putEmbedding("mem_1", "m", floatArrayOf(1f))
        s.writeLastRecall("explain text")
        s.wipe()
        s.memories() shouldBe emptyMap()
        s.edges() shouldBe emptyList()
        s.attributes() shouldBe emptyMap()
        s.readLastRecall() shouldBe null
    }
})
