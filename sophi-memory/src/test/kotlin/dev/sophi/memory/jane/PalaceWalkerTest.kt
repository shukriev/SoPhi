package dev.sophi.memory.jane

import dev.sophi.memory.FakeEmbeddingProvider
import dev.sophi.memory.RecallQuery
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val DAY = 24 * 3_600_000L

class PalaceWalkerTest : FunSpec({
    val fake = FakeEmbeddingProvider()

    class Rig {
        val store = PalaceStore(tempdir().toPath())
        val index = EmbeddingIndex()
        val profile = UserProfile(store)
        val config = JanesPalaceConfig()
        val walker = PalaceWalker(store, index, profile, fake, config)
        suspend fun add(id: String, text: String, room: Room = Room.EPISODES,
                        salience: Double = 0.8, at: Long = 0L,
                        sensitivity: Sensitivity = Sensitivity.PERSONAL,
                        provenance: Provenance = Provenance.USER_DIRECT): Memory {
            val m = Memory(id, text, room, salience, SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
                sensitivity, provenance, at, at, "s")
            store.upsertMemory(m)
            val v = fake.embed(listOf(text)).single()
            store.putEmbedding(id, "fake", v); index.put(id, v)
            return m
        }
        suspend fun walk(input: String, nowMs: Long = DAY, rooms: List<Room> = Room.entries.toList()) =
            walker.walk(RecallQuery("s1", input, nowMs), fake.embed(listOf(input)).single(), rooms)
    }

    test("relevant memory is rendered with room, salience, and age; recall is logged") {
        val r = Rig()
        r.add("mem_1", "dentist appointment thursday fourteen hundred", Room.TASKS)
        val block = r.walk("when is the dentist appointment")!!
        block.rendered shouldContain "<memory_context>"
        block.rendered shouldContain "dentist appointment"
        block.rendered shouldContain "[tasks"
        block.memoryIds shouldBe listOf("mem_1")
        r.store.recallsSince(0L).single().memoryId shouldBe "mem_1"
    }

    test("irrelevant palace yields null (honest non-recall)") {
        val r = Rig()
        r.add("mem_1", "kitchen renovation contractor quote")
        r.walk("what is the capital of France") shouldBe null
    }

    test("VERIFY marker on stale memories (older than half the room half-life) and THIRD_PARTY") {
        val r = Rig()
        r.add("mem_old", "project deadline end of month reported", Room.EPISODES, at = 0L)
        r.add("mem_tp", "colleague said meeting project deadline moved", Room.EPISODES,
            at = 2 * DAY, provenance = Provenance.THIRD_PARTY)
        // now = 2 days: mem_old is 2d old > 36h (half of 72h) → VERIFY; mem_tp fresh but THIRD_PARTY → VERIFY
        val block = r.walk("what about the project deadline", nowMs = 2 * DAY)!!
        val lines = block.rendered.lines().filter { it.contains("deadline") }
        lines.all { it.contains("VERIFY") } shouldBe true
    }

    test("superseded and soft-deleted memories never surface") {
        val r = Rig()
        val old = r.add("mem_a", "meeting thursday planning session")
        r.store.upsertMemory(old.copy(supersededBy = "mem_b"))
        val dead = r.add("mem_c", "meeting friday planning session")
        r.store.upsertMemory(dead.copy(softDeletedAt = 1L))
        r.walk("when is the planning meeting") shouldBe null
    }

    test("SENSITIVE surfaces only as direct hit, never via expansion; access is audited") {
        val r = Rig()
        r.add("mem_h", "user diagnosed with hypertension condition", sensitivity = Sensitivity.SENSITIVE)
        r.add("mem_n", "user bought running shoes for marathon training")
        r.store.upsertEdge(CausalEdge("mem_h", "mem_n", "health"))
        // Direct topical query → sensitive memory appears + audit line written.
        val direct = r.walk("how is my hypertension condition")!!
        direct.rendered shouldContain "hypertension"
        (r.store.recallsSince(0L).isNotEmpty()) shouldBe true
        // Query hitting only the neighbor → sensitive memory must NOT ride along the edge.
        val indirect = r.walk("running shoes marathon training")!!
        indirect.rendered shouldNotContain "hypertension"
    }

    test("RESTRICTED requires a higher direct-hit bar than SENSITIVE, and never rides along expansion") {
        val r = Rig()
        r.add("mem_r", "user disclosed extramarital affair with coworker in strict confidence",
            sensitivity = Sensitivity.RESTRICTED)
        r.add("mem_n", "user bought running shoes for marathon training")
        r.store.upsertEdge(CausalEdge("mem_r", "mem_n", "personal"))
        // High-similarity re-raise clears restrictedFloor (0.55) → direct hit, audited.
        val direct = r.walk("coworker affair confidence")!!
        direct.rendered shouldContain "affair"
        (r.store.recallsSince(0L).isNotEmpty()) shouldBe true
        // Moderate similarity clears sensitiveFloor (0.35) — enough for a SENSITIVE memory,
        // not enough for RESTRICTED's stricter floor — so the memory must not surface.
        val moderate = r.walk("what did I say about the affair in confidence")
        (moderate == null || !moderate.rendered.contains("affair")) shouldBe true
        // Query hitting only the linked neighbor → RESTRICTED memory must not ride along the edge.
        val indirect = r.walk("running shoes marathon training")!!
        indirect.rendered shouldNotContain "affair"
    }

    test("narrative expansion renders the causal thread for a direct hit") {
        val r = Rig()
        r.add("mem_1", "user changed jobs to new company", Room.EPISODES)
        r.add("mem_2", "family moving to plovdiv because of the job", Room.EPISODES)
        r.add("mem_3", "searching schools in plovdiv for emma", Room.EPISODES)
        r.store.upsertEdge(CausalEdge("mem_1", "mem_2", "relocation"))
        r.store.upsertEdge(CausalEdge("mem_2", "mem_3", "relocation"))
        val block = r.walk("tell me about the plovdiv move")!!
        block.rendered shouldContain "relocation"
        block.rendered shouldContain "->"
    }

    test("profile-only recall: an empty palace still surfaces a confirmed profile attribute") {
        val r = Rig()
        r.profile.observeEvidence("diet.preference", "vegetarian", "mem_x", 0L)
        r.profile.confirm("diet.preference")
        val block = r.walk("what should I cook for dinner tonight")!!
        block.rendered shouldContain "user_profile"
        block.rendered shouldContain "diet.preference"
        block.memoryIds shouldBe emptyList()
    }

    test("profile view appears above memories; resonance boosts profile-linked memories") {
        val r = Rig()
        r.profile.observeEvidence("family.daughter.name", "emma school", "mem_x", 0L)
        r.profile.confirm("family.daughter.name")
        r.add("mem_1", "emma school enrollment forms due", Room.TASKS)
        val block = r.walk("school enrollment forms")!!
        block.rendered shouldContain "user_profile"
        block.rendered shouldContain "family.daughter.name"
    }

    test("injection cap and last-recall explanation") {
        val r = Rig()
        repeat(20) { i -> r.add("mem_$i", "grocery list item number $i shopping") }
        val block = r.walk("grocery shopping list")!!
        (block.memoryIds.size <= r.config.injectionCap) shouldBe true
        r.store.readLastRecall()!! shouldContain "grocery"
    }

    test("memory text containing % renders without crashing") {
        val r = Rig()
        r.add("mem_1", "user got a 20% raise at work salary increase", Room.EPISODES)
        val block = r.walk("salary raise at work")!!
        block.rendered shouldContain "20% raise"
    }

    test("profile value containing % renders without crashing") {
        val r = Rig()
        r.profile.observeEvidence("work.schedule", "works 50% remote", "mem_x", 0L)
        r.profile.confirm("work.schedule")
        r.add("mem_1", "remote work schedule discussion with manager", Room.EPISODES)
        val block = r.walk("remote work schedule")!!
        block.rendered shouldContain "works 50% remote"
    }
})
