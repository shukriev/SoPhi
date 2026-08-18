package dev.sophi.memory.jane

import dev.sophi.memory.FakeEmbeddingProvider
import dev.sophi.memory.TurnObservation
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class MemoryWriterTest : FunSpec({
    val embeddings = FakeEmbeddingProvider()
    fun rig(): Pair<PalaceStore, MemoryWriter> {
        val store = PalaceStore(tempdir().toPath())
        val writer = MemoryWriter(store, UserProfile(store), embeddings, "fake", JanesPalaceConfig())
        return Pair(store, writer)
    }
    val turn = TurnObservation("s1", "u", "a", 1_000L)
    fun vm(text: String, room: String = "EPISODES", emph: Double = 0.0, aff: Double = 0.0) =
        VerdictMemory(text = text, room = room, emph = emph, aff = aff)

    test("high-signal memory is stored with blended salience; embedding is retrievable") {
        val (store, writer) = rig()
        // rep=0, nov=1 (empty room), rec=1: α = 0.25*0.8 + 0.15*1 + 0.30*0.9 + 0.10*1 = 0.72
        val stored = writer.write(turn, EncoderVerdict(listOf(vm("User was diagnosed with X", emph = 0.8, aff = 0.9))))
        val m = stored.single()
        (m.salience in 0.70..0.74) shouldBe true
        store.memories().containsKey(m.id) shouldBe true
        store.vectorFor(m.id) shouldBe store.embeddings().getValue(m.id)
    }

    test("below-threshold memory is not stored (θ=0.35)") {
        val (store, writer) = rig()
        // emph=0, aff=0, nov=1, rec=1: α = 0.15 + 0.10 = 0.25 < 0.35
        writer.write(turn, EncoderVerdict(listOf(vm("weather was fine")))) shouldBe emptyList()
        store.memories() shouldBe emptyMap()
    }

    test("near-duplicate merges: existing reinforced, no new memory") {
        val (store, writer) = rig()
        val first = writer.write(turn, EncoderVerdict(listOf(vm("dentist appointment thursday at two", "TASKS", emph = 0.8)))).single()
        val later = turn.copy(nowMs = 2_000L)
        val out = writer.write(later, EncoderVerdict(listOf(vm("dentist appointment thursday at two", "TASKS", emph = 0.8))))
        out shouldBe emptyList()
        val merged = store.memories().getValue(first.id)
        merged.reinforcedAt shouldBe 2_000L
        (merged.salience > first.salience) shouldBe true
        store.memories().size shouldBe 1
    }

    test("supersedes marks the old memory and reroutes its edges") {
        val (store, writer) = rig()
        val old = writer.write(turn, EncoderVerdict(listOf(vm("meeting is on Thursday", "TASKS", emph = 0.9)))).single()
        store.upsertMemory(Memory("mem_root", "root", Room.EPISODES, 0.5,
            SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0), Sensitivity.PERSONAL, Provenance.USER_DIRECT, 0L, 0L, "s"))
        store.upsertEdge(CausalEdge("mem_root", old.id, "planning"))
        val new = writer.write(turn.copy(nowMs = 2_000L), EncoderVerdict(listOf(
            vm("meeting moved to Friday", "TASKS", emph = 0.9).copy(supersedes = old.id)))).single()
        store.memories().getValue(old.id).supersededBy shouldBe new.id
        store.edges().single { it.fromId == "mem_root" }.toId shouldBe new.id
    }

    test("causedBy creates labeled edges; profile evidence lands; text is redacted") {
        val (store, writer) = rig()
        val cause = writer.write(turn, EncoderVerdict(listOf(vm("User changed jobs", "EPISODES", emph = 0.9)))).single()
        val effect = writer.write(turn.copy(nowMs = 2_000L), EncoderVerdict(
            memories = listOf(vm("Family is moving to Plovdiv, password is hunter2", "EPISODES", emph = 0.9)
                .copy(causedBy = listOf(cause.id), thread = "relocation")),
            profile = listOf(VerdictProfile("home.city", "Plovdiv")))).single()
        effect.text shouldNotContain "hunter2"
        val edge = store.edges().single()
        edge.fromId shouldBe cause.id; edge.toId shouldBe effect.id; edge.threadLabel shouldBe "relocation"
        store.attributes().getValue("home.city").value shouldBe "Plovdiv"
    }

    test("explicit profile evidence starts at 0.8 confidence; merely-mentioned starts at 0.5") {
        val (store, writer) = rig()
        writer.write(turn, EncoderVerdict(
            memories = listOf(vm("User asked to remember their commute time", emph = 0.9)),
            profile = listOf(VerdictProfile("commute.time", "45 minutes", explicit = true))))
        store.attributes().getValue("commute.time").confidence shouldBe 0.8

        writer.write(turn.copy(nowMs = 2_000L), EncoderVerdict(
            memories = listOf(vm("User mentioned working from the office", emph = 0.9)),
            profile = listOf(VerdictProfile("work.location", "office", explicit = false))))
        store.attributes().getValue("work.location").confidence shouldBe 0.5
    }

    test("unknown room or causedBy id degrades gracefully (skip memory / skip link)") {
        val (store, writer) = rig()
        writer.write(turn, EncoderVerdict(listOf(vm("x", room = "GARAGE", emph = 0.9)))) shouldBe emptyList()
        val m = writer.write(turn, EncoderVerdict(listOf(
            vm("real fact", emph = 0.9).copy(causedBy = listOf("mem_ghost"))))).single()
        store.edges() shouldBe emptyList()
        store.memories().containsKey(m.id) shouldBe true
    }
})
