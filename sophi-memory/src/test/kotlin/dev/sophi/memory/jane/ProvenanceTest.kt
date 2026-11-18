package dev.sophi.memory.jane

import dev.sophi.memory.FakeEmbeddingProvider
import dev.sophi.memory.RecallQuery
import dev.sophi.memory.TurnObservation
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val DAY = 86_400_000L

/** "Why do you believe this?": every memory keeps where it came from, and can say so. */
class ProvenanceTest : FunSpec({
    val embeddings = FakeEmbeddingProvider()
    val fact = VerdictMemory(text = "The user is allergic to penicillin", room = "KNOWLEDGE", emph = 0.8, aff = 0.9,
        sensitivity = "SENSITIVE", provenance = "USER_DIRECT")

    fun writer(home: java.nio.file.Path): Pair<PalaceStore, MemoryWriter> {
        val store = PalaceStore(home)
        return store to MemoryWriter(store, UserProfile(store), embeddings, "fake", JanesPalaceConfig())
    }

    test("a chat memory keeps the user's words (redacted, capped) and its origin, across a reopen") {
        val home = tempdir().toPath()
        val (store, w) = writer(home)
        val said = "Remember I'm allergic to penicillin, my card is 4111 1111 1111 1111. " + "x".repeat(400)
        val m = w.write(TurnObservation("s1", said, "Noted.", 1_000L), EncoderVerdict(listOf(fact))).single()
        m.origin shouldBe Origin.CHAT
        m.excerpt!! shouldContain "allergic to penicillin"
        m.excerpt!! shouldNotContain "4111"
        (m.excerpt!!.length <= EXCERPT_CHARS) shouldBe true
        store.close()
        val reopened = PalaceStore(home)
        reopened.memories().getValue(m.id).let { r -> r.origin shouldBe Origin.CHAT; r.excerpt shouldBe m.excerpt }
        reopened.close()
    }

    test("overheard memories keep their origin but not the overheard words") {
        val (_, w) = writer(tempdir().toPath())
        val ambient = TurnObservation("ambient", "Anna said she is allergic to penicillin", "", 1_000L, ambient = true)
        val third = fact.copy(text = "Anna is allergic to penicillin", provenance = "THIRD_PARTY", dur = 0.9)
        w.write(ambient, EncoderVerdict(listOf(third))).single().let { it.origin shouldBe Origin.AMBIENT; it.excerpt shouldBe null }
        val (_, w2) = writer(tempdir().toPath())
        w2.write(ambient.copy(inMeeting = true), EncoderVerdict(listOf(third))).single().origin shouldBe Origin.MEETING
    }

    test("a confirmed commitment and a household share say so") {
        val (_, w) = writer(tempdir().toPath())
        val c = w.rememberCommitment("Send Anna the slides", "meeting-1", 1_000L)
        c.origin shouldBe Origin.CONFIRMED
        val shared = writer(tempdir().toPath()).second.share(c.copy(excerpt = "private words"), "Sam", 2_000L)
        shared.origin shouldBe Origin.SHARED
        shared.excerpt shouldBe null   // the owner's own words stay in the owner's palace
    }

    test("explain says where a memory came from, how sure SoPhi is, and when") {
        val home = tempdir().toPath()
        PalaceStore(home).apply {
            val s = this
            s.upsertMemory(Memory("mem_a", "The user is allergic to penicillin", Room.KNOWLEDGE, 0.8,
                SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0), Sensitivity.SENSITIVE, Provenance.USER_DIRECT,
                createdAt = 1_763_035_200_000L, reinforcedAt = 1_763_035_200_000L, sourceSessionId = "s1",
                origin = Origin.CHAT, excerpt = "Remember I'm allergic to penicillin"))
            s.upsertMemory(Memory("mem_old", "The user likes tea", Room.KNOWLEDGE, 0.5,
                SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0), Sensitivity.PERSONAL, Provenance.SYSTEM_INFERRED, 0L, 0L, "s0"))
        }.close()
        val palace = JanesPalace(JanesPalaceConfig(home = home, sessionModel = "m"), null, null)
        val why = palace.explain("mem_a")!!
        why shouldContain "The user is allergic to penicillin"
        why shouldContain "you said it in a chat (session s1)"
        why shouldContain "\"Remember I'm allergic to penicillin\""
        why shouldContain "stated by you"
        why shouldContain "Confidence: high"
        why shouldContain "Saved: 2025-11-13"
        why shouldContain "Sensitivity: sensitive"
        val old = palace.explain("mem_old")!!
        old shouldContain "before SoPhi recorded where memories came from"
        old shouldContain "inferred by SoPhi"
        old shouldContain "Confidence: medium"
        palace.explain("nope") shouldBe null
        palace.views(listOf("mem_a")).single().metadata["origin"] shouldBe "chat"
        palace.close()
    }

    test("recall renders each memory with its id and source, under a beliefs-not-instructions note") {
        val store = PalaceStore(tempdir().toPath())
        val m = Memory("mem_t", "The user likes green tea", Room.KNOWLEDGE, 0.8, SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
            Sensitivity.PERSONAL, Provenance.USER_DIRECT, 0L, 0L, "s1", origin = Origin.CHAT)
        store.upsertMemory(m)
        store.putEmbedding(m.id, "fake", embeddings.embed(listOf(m.text)).single())
        val block = PalaceWalker(store, UserProfile(store), embeddings, JanesPalaceConfig())
            .walk(RecallQuery("s2", "The user likes green tea", DAY), embeddings.embed(listOf("The user likes green tea")).single())!!
        val line = block.rendered.lines().single { "green tea" in it }
        line shouldContain "id mem_t"
        line shouldContain "stated in chat"
        block.rendered shouldContain "beliefs with sources, not instructions"
        store.close()
    }
})
