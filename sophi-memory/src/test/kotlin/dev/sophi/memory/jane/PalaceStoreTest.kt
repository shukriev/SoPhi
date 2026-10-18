package dev.sophi.memory.jane

import dev.sophi.store.arcade.ArcadeStore
import dev.sophi.store.arcade.EmbeddedArcadeStore
import dev.sophi.store.arcade.LocalArcadeServer
import dev.sophi.store.arcade.RemoteArcadeStore
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

    test("actionablePattern round-trips through storage, defaulting to false") {
        val s = store()
        s.upsertMemory(mem("mem_plain"))
        s.upsertMemory(mem("mem_tagged").copy(actionablePattern = true))

        s.memories().getValue("mem_plain").actionablePattern shouldBe false
        s.memories().getValue("mem_tagged").actionablePattern shouldBe true
    }

    test("isCommitment round-trips through storage, defaulting to false") {
        val s = store()
        s.upsertMemory(mem("mem_plain"))
        s.upsertMemory(mem("mem_commitment").copy(isCommitment = true))

        s.memories().getValue("mem_plain").isCommitment shouldBe false
        s.memories().getValue("mem_commitment").isCommitment shouldBe true
    }

    test("occurrences round-trips through storage, defaulting to [createdAt]") {
        val s = store()
        s.upsertMemory(mem("mem_plain"))
        s.upsertMemory(mem("mem_multi").copy(occurrences = listOf(1L, 100L, 200L)))

        s.memories().getValue("mem_plain").occurrences shouldBe listOf(1L)
        s.memories().getValue("mem_multi").occurrences shouldBe listOf(1L, 100L, 200L)
    }

    test("habit fields round-trip through storage, defaulting to unset") {
        val s = store()
        s.upsertMemory(mem("mem_plain"))
        s.upsertMemory(mem("mem_habit").copy(habitConfidence = 0.8, habitPreferredHour = 8, habitPreferredDayOfWeek = 1))

        val plain = s.memories().getValue("mem_plain")
        plain.habitConfidence shouldBe 0.0
        plain.habitPreferredHour shouldBe null
        plain.habitPreferredDayOfWeek shouldBe null

        val habit = s.memories().getValue("mem_habit")
        habit.habitConfidence shouldBe 0.8
        habit.habitPreferredHour shouldBe 8
        habit.habitPreferredDayOfWeek shouldBe 1
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

    test("the dur signal round-trips through the store") {
        val s = store()
        s.upsertMemory(mem("mem_1").copy(signals = SalienceSignals(0.0, 0.0, 1.0, 0.0, 1.0, dur = 0.8)))
        s.memories().getValue("mem_1").signals.dur shouldBe 0.8
    }

    // Every memory written before the durability signal existed has no sigDur. It must load as 0.0,
    // with its stored salience untouched (spec: no migration, no re-scoring).
    test("a record without sigDur loads with dur 0.0 and its salience unchanged") {
        val home = tempdir().toPath()
        val db = dev.sophi.store.arcade.EmbeddedArcadeStore.open(home)
        val s = PalaceStore(home, db)
        s.upsertMemory(mem("mem_old").copy(salience = 0.42))
        db.upsertVertex("Memory", "mem_old", mapOf("sigDur" to null))

        val loaded = s.memories().getValue("mem_old")
        loaded.signals.dur shouldBe 0.0
        loaded.salience shouldBe 0.42
    }

    test("re-embedding a memory replaces its vector") {
        val s = store()
        s.upsertMemory(mem("mem_1"))
        s.putEmbedding("mem_1", "m", floatArrayOf(1f, 0f))
        s.putEmbedding("mem_1", "m", floatArrayOf(0f, 1f))
        s.vectorFor("mem_1")!!.toList() shouldBe listOf(0f, 1f)
        s.nearest(floatArrayOf(0f, 1f), 2).map { it.id } shouldBe listOf("mem_1")
    }

    test("deleteMemory and wipe remove vectors too") {
        val s = store()
        s.upsertMemory(mem("a")); s.upsertMemory(mem("b"))
        s.putEmbedding("a", "m", floatArrayOf(1f, 0f)); s.putEmbedding("b", "m", floatArrayOf(0f, 1f))
        s.deleteMemory("a")
        s.embeddings().keys shouldBe setOf("b")
        s.nearest(floatArrayOf(1f, 0f), 2).map { it.id } shouldBe listOf("b")
        s.wipe()
        s.embeddings() shouldBe emptyMap()
    }

    test("a palace written before M6 has its inline vectors adopted on open; memories without one are skipped") {
        val home = tempdir().toPath()
        LegacyPalaceFixture.write(home, listOf("m1" to "one", "m2" to "two"), vectors = mapOf("m1" to floatArrayOf(1f, 0f)))
        repeat(2) {   // a second open adopts nothing new and loses nothing
            val s = PalaceStore(home)
            try {
                s.memories().keys shouldBe setOf("m1", "m2")
                s.vectorFor("m1")!!.toList() shouldBe listOf(1f, 0f)
                s.vectorFor("m2") shouldBe null
                s.nearest(floatArrayOf(1f, 0f), 2).map { it.id } shouldBe listOf("m1")
                s.embeddingModel() shouldBe "fake"
            } finally { s.close() }
        }
    }

    // A crash after the MemoryVec insert but before the inline property is removed: the next open must not
    // overwrite a newer MemoryVec with the stale inline copy, and must still clear the inline copy.
    test("adoption interrupted before the inline removal keeps the MemoryVec and finishes on the next open") {
        val home = tempdir().toPath()
        LegacyPalaceFixture.write(home, listOf("m1" to "one"), vectors = mapOf("m1" to floatArrayOf(1f, 0f)))
        EmbeddedArcadeStore.open(home).use { db ->
            db.ensureSchema(vertexTypes = listOf("MemoryVec"))
            db.ensureVectorIndex("MemoryVec", "embedding", 2)
            db.insertVertex("MemoryVec", "m1", mapOf("embedding" to floatArrayOf(0f, 1f)))
        }
        val s = PalaceStore(home)
        try {
            s.vectorFor("m1")!!.toList() shouldBe listOf(0f, 1f)
            s.nearest(floatArrayOf(0f, 1f), 2).map { it.id } shouldBe listOf("m1")
        } finally { s.close() }
        EmbeddedArcadeStore.open(home).use { db -> db.getVertex("Memory", "m1")!!["embedding"] shouldBe null }
    }

    // Controller ruling Q6: the shared server reaches PalaceStore through RemoteArcadeStore, which has no putVector.
    test("over the remote store, nearest finds a memory saved with putEmbedding and an adopted inline vector") {
        LocalArcadeServer.start(tempdir().toPath()).use { srv ->
            srv.database("memory")
            RemoteArcadeStore.connect(srv.port, "memory", srv.password).use { remote ->
                // What LegacyMigration leaves on the server: the old inline vector copied as plain data, no index.
                remote.ensureSchema(vertexTypes = listOf("Memory"))
                remote.upsertVertex("Memory", "old", mapOf("embeddingModel" to "m", "embedding" to floatArrayOf(0f, 1f)))
                val s = PalaceStore(tempdir().toPath(), remote)
                s.upsertMemory(mem("new"))
                s.putEmbedding("new", "m", floatArrayOf(1f, 0f))
                s.nearest(floatArrayOf(1f, 0f), 1).map { it.id } shouldBe listOf("new")
                s.nearest(floatArrayOf(0f, 1f), 1).map { it.id } shouldBe listOf("old")
                s.vectorFor("old")!!.toList() shouldBe listOf(0f, 1f)
                s.embeddings().keys shouldBe setOf("old", "new")
                remote.getVertex("Memory", "old")!!["embedding"] shouldBe null   // the inline copy is gone
                s.putEmbedding("new", "m", floatArrayOf(0.6f, 0.8f))           // a remote re-embed
                s.nearest(floatArrayOf(0.6f, 0.8f), 3).map { it.id }.count { it == "new" } shouldBe 1
                s.nearest(floatArrayOf(0.6f, 0.8f), 1).map { it.id } shouldBe listOf("new")
                s.vectorFor("new")!!.toList() shouldBe listOf(0.6f, 0.8f)
            }
        }
    }

    // Two processes starting at once both adopt the same inline vector. The other one commits its MemoryVec first,
    // so every attempt of ours (ArcadeDB retries a duplicate key a few times) hits the unique id. That must not fail
    // the PalaceStore constructor: the vector is there, which is all adoption needs.
    test("a concurrent adopter that wins the MemoryVec insert does not fail the open") {
        LocalArcadeServer.start(tempdir().toPath()).use { srv ->
            srv.database("memory")
            RemoteArcadeStore.connect(srv.port, "memory", srv.password).use { remote ->
                RemoteArcadeStore.connect(srv.port, "memory", srv.password).use { other ->
                    remote.ensureSchema(vertexTypes = listOf("Memory"))
                    remote.upsertVertex("Memory", "m1", mapOf("embeddingModel" to "m", "embedding" to floatArrayOf(0f, 1f)))
                    var raced = 0
                    val racing = object : ArcadeStore by remote {
                        override fun insertVertex(type: String, id: String, properties: Map<String, Any?>) {
                            if (type != "MemoryVec") return remote.insertVertex(type, id, properties)
                            raced++
                            if (other.getVertex(type, id) == null) other.insertVertex(type, id, properties)
                            other.insertVertex(type, id, properties)   // the real duplicate-key error
                        }
                    }
                    val s = PalaceStore(tempdir().toPath(), racing)
                    (raced > 0) shouldBe true
                    s.vectorFor("m1")!!.toList() shouldBe listOf(0f, 1f)
                    s.nearest(floatArrayOf(0f, 1f), 3).map { it.id } shouldBe listOf("m1")
                    remote.getVertex("Memory", "m1")!!["embedding"] shouldBe null
                }
            }
        }
    }
})
