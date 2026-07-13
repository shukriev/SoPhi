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

    test("rewriteAll leaves no trace of dropped records") {
        val s = store()
        s.upsertMemory(mem("mem_1")); s.upsertMemory(mem("mem_2"))
        s.putEmbedding("mem_1", "m", floatArrayOf(1f)); s.putEmbedding("mem_2", "m", floatArrayOf(2f))
        s.upsertEdge(CausalEdge("mem_1", "mem_2", "t"))
        s.logRecall(RecallRecord(1L, "mem_1", "s"))
        val kept = s.memories().getValue("mem_2")
        s.rewriteAll(listOf(kept), emptyList(), emptyList(), mapOf("mem_2" to floatArrayOf(2f)), "m", emptyList())
        s.memories().keys shouldBe setOf("mem_2")
        s.edges() shouldBe emptyList()
        s.embeddings().keys shouldBe setOf("mem_2")
        s.recallsSince(0L) shouldBe emptyList()
    }

    test("consolidation marker round-trips; absent initially") {
        val s = store()
        s.lastConsolidationMs() shouldBe null
        s.markConsolidation(42L)
        s.lastConsolidationMs() shouldBe 42L
    }

    test("malformed jsonl lines are skipped, not fatal") {
        val dir = tempdir().toPath()
        val s = PalaceStore(dir)
        s.upsertMemory(mem("mem_1"))
        java.nio.file.Files.write(dir.resolve("memories.jsonl"),
            "{not json}\n".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        s.memories().keys shouldBe setOf("mem_1")
    }
})
