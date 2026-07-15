package dev.sophi.memory.jane

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.memory.FakeEmbeddingProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk

private const val DAY = 24 * 3_600_000L

class ConsolidatorTest : FunSpec({
    val fake = FakeEmbeddingProvider()

    class Rig(provider: LLMProvider? = null) {
        val store = PalaceStore(tempdir().toPath())
        val index = EmbeddingIndex()
        val config = JanesPalaceConfig(sessionModel = "test-model")
        val engine = ForgetEngine(store, index, UserProfile(store))
        val consolidator = Consolidator(store, index, engine, provider, config)
        suspend fun add(id: String, text: String, room: Room = Room.EPISODES,
                        salience: Double = 0.8, at: Long = 0L, softDeletedAt: Long? = null): Memory {
            val m = Memory(id, text, room, salience, SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
                Sensitivity.PERSONAL, Provenance.USER_DIRECT, at, at, "s", softDeletedAt = softDeletedAt)
            store.upsertMemory(m)
            val v = fake.embed(listOf(text)).single()
            store.putEmbedding(id, "fake", v); index.put(id, v)
            return m
        }
    }

    test("merge: near-duplicates within a room collapse, salience strengthened, clock reset") {
        val r = Rig()
        r.add("mem_a", "dentist appointment thursday fourteen", at = 0L, salience = 0.6)
        r.add("mem_b", "dentist appointment thursday fourteen", at = 100L, salience = 0.5)
        val report = r.consolidator.run(nowMs = 1_000L)
        report.merged shouldBe 1
        val survivors = r.store.memories().values.filter { it.active }
        survivors.size shouldBe 1
        (survivors.single().salience > 0.6) shouldBe true
        survivors.single().reinforcedAt shouldBe 1_000L
    }

    test("merge: 3-way chain accumulates salience bumps on the running survivor, not the stale snapshot") {
        val r = Rig()
        r.add("mem_a", "dentist appointment thursday fourteen", at = 0L, salience = 0.5)
        r.add("mem_b", "dentist appointment thursday fourteen", at = 100L, salience = 0.5)
        r.add("mem_c", "dentist appointment thursday fourteen", at = 200L, salience = 0.5)
        val report = r.consolidator.run(nowMs = 1_000L)
        report.merged shouldBe 2
        val survivors = r.store.memories().values.filter { it.active }
        survivors.size shouldBe 1
        survivors.single().salience shouldBe (0.6 plusOrMinus 1e-9)
    }

    test("strengthen: memories recalled twice today get their decay clock reset") {
        val r = Rig()
        r.add("mem_a", "kitchen renovation quote received", at = 0L)
        r.store.logRecall(RecallRecord(10 * DAY, "mem_a", "s"))
        r.store.logRecall(RecallRecord(10 * DAY + 1, "mem_a", "s"))
        val report = r.consolidator.run(nowMs = 10 * DAY + 2)
        report.strengthened shouldBe 1
        r.store.memories().getValue("mem_a").reinforcedAt shouldBe 10 * DAY + 2
    }

    test("compress: old low-priority thread becomes one SYSTEM_INFERRED summary preserving endpoints") {
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any()) } returns
            LLMResponse.Text("Job change led to relocation to Plovdiv", TokenUsage(1, 1))
        val r = Rig(provider)
        val now = 200 * DAY
        r.add("mem_1", "user changed jobs", at = 0L, salience = 0.05)
        r.add("mem_2", "family moved to plovdiv", at = 1 * DAY, salience = 0.05)
        r.add("mem_3", "emma enrolled in new school", at = 2 * DAY, salience = 0.05)
        r.store.upsertEdge(CausalEdge("mem_1", "mem_2", "relocation"))
        r.store.upsertEdge(CausalEdge("mem_2", "mem_3", "relocation"))
        val report = r.consolidator.run(nowMs = now)
        report.compressed shouldBe 1
        val summary = r.store.memories().values.single {
            it.provenance == Provenance.SYSTEM_INFERRED && it.active }
        summary.text shouldBe "Job change led to relocation to Plovdiv"
        summary.room shouldBe Room.NARRATIVE
        // Endpoints preserved through the summary node.
        val edges = r.store.edges()
        (edges.any { it.fromId == "mem_1" && it.toId == summary.id }) shouldBe true
        (edges.any { it.fromId == summary.id && it.toId == "mem_3" }) shouldBe true
        // Interior node soft-deleted.
        (r.store.memories().getValue("mem_2").softDeletedAt != null) shouldBe true
    }

    test("compress is skipped without a provider") {
        val r = Rig(provider = null)
        val now = 200 * DAY
        r.add("mem_1", "a", at = 0L, salience = 0.05); r.add("mem_2", "b", at = 1L, salience = 0.05)
        r.store.upsertEdge(CausalEdge("mem_1", "mem_2", "t"))
        r.consolidator.run(nowMs = now).compressed shouldBe 0
    }

    test("prune soft-deletes orphans below the floor; purge drops soft-deleted past grace") {
        val r = Rig()
        val now = 400 * DAY
        r.add("mem_dead", "long forgotten trivia", at = 0L, salience = 0.5)          // decayed ≈ 0
        r.add("mem_live", "recent important fact", at = now, salience = 0.9)
        r.add("mem_old_soft", "already soft deleted", at = 0L, softDeletedAt = now - 31 * DAY)
        val report = r.consolidator.run(nowMs = now)
        report.pruned shouldBe 1
        report.purged shouldBe 1
        (r.store.memories().getValue("mem_dead").softDeletedAt != null) shouldBe true
        r.store.memories().containsKey("mem_old_soft") shouldBe false
        r.store.memories().getValue("mem_live").active shouldBe true
    }

    test("isDue respects the 24h marker") {
        val r = Rig()
        r.consolidator.isDue(nowMs = 0L) shouldBe true
        r.store.markConsolidation(0L)
        r.consolidator.isDue(nowMs = 1_000L) shouldBe false
        r.consolidator.isDue(nowMs = 25 * 3_600_000L) shouldBe true
    }
})
