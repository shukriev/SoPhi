package dev.sophi.memory.jane

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class UserProfileTest : FunSpec({
    fun profile() = UserProfile(PalaceStore(tempdir().toPath()))

    test("new evidence creates attribute at 0.5; corroboration raises by 0.15 capped at 1.0") {
        val p = profile()
        p.observeEvidence("family.daughter.name", "Emma", "mem_1", 1L)
        p.all().getValue("family.daughter.name").confidence shouldBe (0.5 plusOrMinus 1e-9)
        p.observeEvidence("family.daughter.name", "Emma", "mem_2", 2L)
        val a = p.all().getValue("family.daughter.name")
        a.confidence shouldBe (0.65 plusOrMinus 1e-9)
        a.evidenceCount shouldBe 2
        a.evidenceMemoryIds shouldBe listOf("mem_1", "mem_2")
    }

    test("contradiction lowers confidence; below 0.3 the new value takes over at 0.5") {
        val p = profile()
        p.observeEvidence("job.title", "teacher", "mem_1", 1L)          // 0.5
        p.observeEvidence("job.title", "principal", "mem_2", 2L)        // 0.25 -> replace
        val a = p.all().getValue("job.title")
        a.value shouldBe "principal"
        a.confidence shouldBe (0.5 plusOrMinus 1e-9)
        a.evidenceMemoryIds shouldBe listOf("mem_2")
    }

    test("view applies the confidence floor") {
        val p = profile()
        p.observeEvidence("a.low", "x", "mem_1", 1L)                     // 0.5 — below floor
        p.confirm("a.low") shouldBe true                                 // 1.0
        p.observeEvidence("b.other", "y", "mem_2", 1L)                   // 0.5
        p.view(0.7).map { it.path } shouldBe listOf("a.low")
    }

    test("correct replaces value at 0.8 with no memory evidence; delete tombstones") {
        val p = profile()
        p.observeEvidence("home.city", "Sofia", "mem_1", 1L)
        p.correct("home.city", "Plovdiv") shouldBe true
        val a = p.all().getValue("home.city")
        a.value shouldBe "Plovdiv"; a.confidence shouldBe (0.8 plusOrMinus 1e-9)
        a.evidenceMemoryIds shouldBe emptyList<String>()
        a.evidenceCount shouldBe 0
        p.delete("home.city") shouldBe true
        p.all().containsKey("home.city") shouldBe false
        p.confirm("nope") shouldBe false
    }

    test("reduceEvidence removes a memory's trace; sole-evidence attribute dies") {
        val p = profile()
        p.observeEvidence("pet.name", "Rex", "mem_1", 1L)
        p.observeEvidence("pet.name", "Rex", "mem_2", 2L)                // conf 0.65, 2 ids
        p.observeEvidence("kid.name", "Ana", "mem_1", 1L)                // sole evidence
        val affected = p.reduceEvidence("mem_1", 3L)
        affected.toSet() shouldBe setOf("pet.name", "kid.name")
        p.all().containsKey("kid.name") shouldBe false
        val pet = p.all().getValue("pet.name")
        pet.evidenceMemoryIds shouldBe listOf("mem_2")
        pet.confidence shouldBe (0.65 * 0.5 plusOrMinus 1e-9)           // scaled by remaining/original
    }
})
