package dev.sophi.memory.jane

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

class MemoryWorthTest : FunSpec({
    val cfg = JanesPalaceConfig()
    fun mem(hitsPositive: Int, hitsNegative: Int) = Memory(
        id = "mem_x", text = "t", room = Room.EPISODES, salience = 0.5,
        signals = SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
        sensitivity = Sensitivity.PERSONAL, provenance = Provenance.USER_DIRECT,
        createdAt = 0L, reinforcedAt = 0L, sourceSessionId = "s",
        hitsPositive = hitsPositive, hitsNegative = hitsNegative
    )

    test("a never-recalled memory is LOW_EVIDENCE, multiplier stays neutral") {
        worthClass(mem(0, 0), cfg) shouldBe WorthClass.LOW_EVIDENCE
        worthMultiplier(mem(0, 0), cfg) shouldBe 1.0
    }

    test("below the evidence floor stays LOW_EVIDENCE even at a 100% success ratio") {
        // default worthMinEvidence = 10; 9 total hits, all positive
        worthClass(mem(9, 0), cfg) shouldBe WorthClass.LOW_EVIDENCE
        worthMultiplier(mem(9, 0), cfg) shouldBe 1.0
    }

    test("HIGH: enough evidence and ratio above the high threshold boosts") {
        worthClass(mem(7, 3), cfg) shouldBe WorthClass.HIGH // 0.70 > 0.60
        worthMultiplier(mem(7, 3), cfg) shouldBe cfg.worthBoostMultiplier
    }

    test("LOW: enough evidence and ratio below the low threshold suppresses") {
        worthClass(mem(3, 7), cfg) shouldBe WorthClass.LOW // 0.30 < 0.40
        worthMultiplier(mem(3, 7), cfg) shouldBe cfg.worthSuppressMultiplier
    }

    test("MIXED: enough evidence but ratio between thresholds stays neutral") {
        worthClass(mem(5, 5), cfg) shouldBe WorthClass.MIXED // 0.50
        worthMultiplier(mem(5, 5), cfg) shouldBe 1.0
    }

    test("boundary: ratio exactly at the high threshold is not yet HIGH (strict greater-than)") {
        // 6/10 = 0.60 == worthHighThreshold, not > it
        worthClass(mem(6, 4), cfg) shouldBe WorthClass.MIXED
    }

    test("boundary: ratio exactly at the low threshold is not yet LOW (strict less-than)") {
        // 4/10 = 0.40 == worthLowThreshold, not < it
        worthClass(mem(4, 6), cfg) shouldBe WorthClass.MIXED
    }

    // applyOutcome: uses a real PalaceStore, same rig style as ConsolidatorTest/PalaceWalkerTest.
    class Rig {
        val store = PalaceStore(tempdir().toPath())
        fun add(id: String): Memory {
            val m = Memory(id, "t", Room.EPISODES, 0.5, SalienceSignals(0.0, 0.0, 0.0, 0.0, 1.0),
                Sensitivity.PERSONAL, Provenance.USER_DIRECT, 0L, 0L, "s")
            store.upsertMemory(m)
            return m
        }
    }

    test("applyOutcome increments hitsPositive for every memory recalled in a successful session") {
        val r = Rig()
        r.add("mem_a"); r.add("mem_b")
        r.store.logRecall(RecallRecord(100L, "mem_a", "sess_1"))
        r.store.logRecall(RecallRecord(200L, "mem_b", "sess_1"))

        applyOutcome(r.store, "sess_1", success = true)

        r.store.memories().getValue("mem_a").hitsPositive shouldBe 1
        r.store.memories().getValue("mem_b").hitsPositive shouldBe 1
        r.store.memories().getValue("mem_a").hitsNegative shouldBe 0
    }

    test("applyOutcome increments hitsNegative on a failed session, not hitsPositive") {
        val r = Rig()
        r.add("mem_a")
        r.store.logRecall(RecallRecord(100L, "mem_a", "sess_1"))

        applyOutcome(r.store, "sess_1", success = false)

        r.store.memories().getValue("mem_a").hitsNegative shouldBe 1
        r.store.memories().getValue("mem_a").hitsPositive shouldBe 0
    }

    test("applyOutcome counts each recall within the session, not just distinct memories") {
        val r = Rig()
        r.add("mem_a")
        r.store.logRecall(RecallRecord(100L, "mem_a", "sess_1"))
        r.store.logRecall(RecallRecord(200L, "mem_a", "sess_1"))

        applyOutcome(r.store, "sess_1", success = true)

        r.store.memories().getValue("mem_a").hitsPositive shouldBe 2
    }

    test("applyOutcome ignores recalls from other sessions") {
        val r = Rig()
        r.add("mem_a")
        r.store.logRecall(RecallRecord(100L, "mem_a", "sess_1"))
        r.store.logRecall(RecallRecord(200L, "mem_a", "sess_2"))

        applyOutcome(r.store, "sess_1", success = true)

        r.store.memories().getValue("mem_a").hitsPositive shouldBe 1
    }

    test("applyOutcome is a no-op for a session with no recalls") {
        val r = Rig()
        r.add("mem_a")

        applyOutcome(r.store, "sess_unknown", success = true)

        r.store.memories().getValue("mem_a").hitsPositive shouldBe 0
    }
})
