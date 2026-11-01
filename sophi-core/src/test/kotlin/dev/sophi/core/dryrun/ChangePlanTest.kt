package dev.sophi.core.dryrun

import dev.sophi.core.audit.auditEntry
import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

private fun plan(id: String = "plan_a", createdAtMs: Long = 1L, vararg steps: Pair<String, String>) = ChangePlan(
    id = id, createdAtMs = createdAtMs, task = "offboard jane", sessionId = "s1", reads = emptyList(),
    steps = steps.mapIndexed { i, (tool, args) -> PlanStep(i + 1, tool, args, RiskLevel.DESTRUCTIVE, allowedByRule = false) },
)

class ChangePlanTest : FunSpec({

    test("record numbers steps from 1 in call order, even from parallel coroutines") {
        val recorder = PlanRecorder(PlanFirst.OUTSIDE)
        val ns = runBlocking(Dispatchers.Default) {
            (1..20).map { i -> async { recorder.record("okta__t$i", "{}", RiskLevel.DESTRUCTIVE, false) } }.awaitAll()
        }
        ns.toSet().size shouldBe 20
        recorder.steps.map { it.n }.sorted() shouldBe (1..20).toList()
        recorder.steps.forEach { recorder.steps.single { s -> s.n == it.n }.tool shouldBe it.tool }
    }

    test("stepsHash ignores dropped steps and changes when an argument changes") {
        val p = plan(steps = arrayOf("okta__a" to """{"id":"1"}""", "github__b" to """{"u":"j"}"""))
        val withDrop = p.copy(dropped = setOf(2))
        withDrop.stepsHash() shouldBe plan(steps = arrayOf("okta__a" to """{"id":"1"}""")).stepsHash()
        p.stepsHash() shouldNotBe withDrop.stepsHash()
        val edited = p.copy(steps = p.steps.map { if (it.n == 1) it.copy(args = """{"id":"2"}""") else it })
        edited.stepsHash() shouldNotBe p.stepsHash()
    }

    test("approvedSteps drops the dropped steps") {
        val p = plan(steps = arrayOf("a__x" to "{}", "b__y" to "{}", "c__z" to "{}")).copy(dropped = setOf(2))
        p.approvedSteps().map { it.n } shouldBe listOf(1, 3)
    }

    test("store round-trips a plan and lists newest first") {
        val store = ChangePlanStore(Files.createTempDirectory("plans"))
        val older = plan("plan_old", 1L, "a__x" to "{}")
        val newer = plan("plan_new", 2L, "a__x" to "{}").copy(status = PlanStatus.APPROVED)
        store.save(older); store.save(newer)
        store.get("plan_new") shouldBe newer
        store.list().map { it.id } shouldBe listOf("plan_new", "plan_old")
    }

    test("a corrupt plan file is skipped by list and get returns null") {
        val dir = Files.createTempDirectory("plans")
        val store = ChangePlanStore(dir)
        store.save(plan("plan_ok", 1L, "a__x" to "{}"))
        Files.writeString(dir.resolve("plan_bad.json"), "{not json")
        store.list().map { it.id } shouldBe listOf("plan_ok")
        store.get("plan_bad") shouldBe null
        store.get("plan_missing") shouldBe null
    }

    test("planStepStub says it didn't run") {
        val stub = planStepStub(3)
        stub shouldContain "Not run"
        stub shouldContain "approve"
        stub shouldContain "step 3"
    }

    test("toPlan carries the recorder's reads and steps") {
        val r = PlanRecorder(PlanFirst.OUTSIDE)
        r.read("okta__find_user", """{"q":"jane"}""", "00u9", viaHint = true)
        r.record("okta__deactivate_user", """{"id":"00u9"}""", RiskLevel.DESTRUCTIVE, allowedByRule = true)
        val p = r.toPlan("offboard jane", "s1")
        p.id.startsWith("plan_") shouldBe true
        p.status shouldBe PlanStatus.PENDING
        p.reads.single().viaHint shouldBe true
        p.steps.single().allowedByRule shouldBe true
    }

    test("an audit entry with outcome recorded has decision held and carries its plan") {
        val e = auditEntry("local", null, "s", "okta__x", RiskLevel.DESTRUCTIVE, "{}", "plan", "recorded", "", 0, plan = "plan_a#1")
        e.decision shouldBe "held"
        e.plan shouldBe "plan_a#1"
        auditEntry("local", null, "s", "t", RiskLevel.SAFE, "{}", "safe", "denied", "", 0).decision shouldBe "denied"
        auditEntry("local", null, "s", "t", RiskLevel.SAFE, "{}", "safe", "ok", "", 0).decision shouldBe "allowed"
    }
})
