package dev.sophi.memory.jane

import dev.sophi.memory.ForgetRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

class ForgetEngineTest : FunSpec({
    class Rig {
        val home = tempdir().toPath()
        val store = PalaceStore(home)
        val profile = UserProfile(store)
        val engine = ForgetEngine(store, profile)
        fun add(id: String, text: String = "text-$id"): Memory {
            val m = Memory(id, text, Room.EPISODES, 0.8, SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
                Sensitivity.PERSONAL, Provenance.USER_DIRECT, 1L, 1L, "s")
            store.upsertMemory(m)
            store.putEmbedding(id, "fake", floatArrayOf(1f, 2f))
            return m
        }
    }

    test("forget removes the memory everywhere and re-links the causal chain compressed") {
        val r = Rig()
        r.add("mem_a"); r.add("mem_b", "the secret rendezvous location"); r.add("mem_c")
        r.store.upsertEdge(CausalEdge("mem_a", "mem_b", "story"))
        r.store.upsertEdge(CausalEdge("mem_b", "mem_c", "story"))
        r.store.logRecall(RecallRecord(5L, "mem_b", "s1"))
        r.store.writeLastRecall("recalled [mem_b] the secret rendezvous location")
        r.profile.observeEvidence("secret.place", "rendezvous", "mem_b", 1L)

        val result = r.engine.forget(ForgetRequest.ById("mem_b"), 10L)
        result.removedIds shouldBe listOf("mem_b")
        result.relinkedEdges shouldBe 1
        result.affectedProfilePaths shouldBe listOf("secret.place")

        r.store.memories().keys shouldBe setOf("mem_a", "mem_c")
        r.store.embeddings().keys shouldBe setOf("mem_a", "mem_c")
        r.store.vectorFor("mem_b") shouldBe null
        val edge = r.store.edges().single()
        edge.fromId shouldBe "mem_a"; edge.toId shouldBe "mem_c"; edge.compressed shouldBe true
        r.store.recallsSince(0L) shouldBe emptyList()
        r.profile.all().containsKey("secret.place") shouldBe false   // sole evidence → gone
    }

    test("preview shows what forget would remove/affect without mutating, then real forget matches") {
        val r = Rig()
        r.add("mem_a"); r.add("mem_b", "the secret rendezvous location"); r.add("mem_c")
        r.store.upsertEdge(CausalEdge("mem_a", "mem_b", "story"))
        r.store.upsertEdge(CausalEdge("mem_b", "mem_c", "story"))
        r.profile.observeEvidence("secret.place", "rendezvous", "mem_b", 1L)

        val preview = r.engine.preview("mem_b")
        preview.removedIds shouldBe listOf("mem_b")
        preview.relinkedEdges shouldBe 1
        preview.affectedProfilePaths shouldBe listOf("secret.place")

        // Non-mutating: store is untouched after the preview.
        r.store.memories().keys shouldBe setOf("mem_a", "mem_b", "mem_c")
        r.profile.all().containsKey("secret.place") shouldBe true

        val result = r.engine.forget(ForgetRequest.ById("mem_b"), 10L)
        result.removedIds shouldBe preview.removedIds
        result.relinkedEdges shouldBe preview.relinkedEdges
        result.affectedProfilePaths shouldBe preview.affectedProfilePaths

        // preview of unknown id returns empty result
        r.engine.preview("mem_zz").removedIds shouldBe emptyList()
    }

    test("forget of unknown id returns empty result") {
        val r = Rig()
        r.add("mem_a")
        r.engine.forget(ForgetRequest.ById("mem_zz"), 10L).removedIds shouldBe emptyList()
        r.store.memories().keys shouldBe setOf("mem_a")
    }

    test("ForgetRequest.All wipes the palace") {
        val r = Rig()
        r.add("mem_a"); r.add("mem_b")
        val result = r.engine.forget(ForgetRequest.All, 10L)
        result.removedIds.toSet() shouldBe setOf("mem_a", "mem_b")
        r.store.memories() shouldBe emptyMap()
        r.store.embeddings() shouldBe emptyMap()
    }

    test("purgeSoftDeleted physically removes only memories past the cutoff") {
        val r = Rig()
        val a = r.add("mem_a"); val b = r.add("mem_b")
        r.store.upsertMemory(a.copy(softDeletedAt = 100L))
        r.store.upsertMemory(b.copy(softDeletedAt = 900L))
        r.engine.purgeSoftDeleted(cutoffMs = 500L, nowMs = 1_000L) shouldBe 1     // only mem_a is old enough
        r.store.memories().keys shouldBe setOf("mem_b")
    }
})
