package dev.sophi.core.dryrun

import dev.sophi.core.audit.AuditEntry
import dev.sophi.core.tools.Reach
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import java.nio.file.Files

private class Recording(override val name: String, private val where: Reach = Reach.OUTSIDE_CHANGE, private val run: suspend (String) -> String = { "ok" }) : Tool {
    val calls = mutableListOf<String>()
    override val description = "fake"
    override val parametersJson = "{}"
    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
    override fun reach(argumentsJson: String) = where
    override suspend fun execute(argumentsJson: String): String { calls += argumentsJson; return run(argumentsJson) }
}

private fun approved(vararg steps: Pair<String, String>, dropped: Set<Int> = emptySet()): ChangePlan {
    val p = ChangePlan("plan_t", 1L, "offboard jane", "s1", actor = "alice", reads = emptyList(),
        steps = steps.mapIndexed { i, (t, a) -> PlanStep(i + 1, t, a, RiskLevel.DESTRUCTIVE, false) }, dropped = dropped)
    return p.copy(status = PlanStatus.APPROVED, approvedAtMs = 2L, approvedHash = p.stepsHash())
}

class PlanReplayerTest : FunSpec({
    fun store() = ChangePlanStore(Files.createTempDirectory("plans"))

    test("replays approved steps in order with the same arguments") {
        val order = mutableListOf<String>()
        val a = Recording("okta__deactivate_user") { order += "a"; "ok" }
        val b = Recording("github__remove_member") { order += "b"; "ok" }
        val s = store()
        val plan = approved("okta__deactivate_user" to """{"id":"00u9"}""", "github__remove_member" to """{"u":"jdoe"}""").also(s::save)
        val done = PlanReplayer(ToolRegistry().register(a).register(b), s).run(plan)

        order shouldBe listOf("a", "b")
        a.calls shouldBe listOf("""{"id":"00u9"}""")
        b.calls shouldBe listOf("""{"u":"jdoe"}""")
        done.status shouldBe PlanStatus.DONE
        done.results.map { it.outcome } shouldBe listOf("ok", "ok")
        s.get("plan_t")!!.status shouldBe PlanStatus.DONE
    }

    test("dropped steps don't run") {
        val a = Recording("a__x"); val b = Recording("b__y")
        val s = store()
        val plan = approved("a__x" to "{}", "b__y" to "{}", dropped = setOf(1)).also(s::save)
        PlanReplayer(ToolRegistry().register(a).register(b), s).run(plan)
        a.calls shouldBe emptyList()
        b.calls.size shouldBe 1
    }

    test("stops at the first failure and marks the rest not run") {
        val a = Recording("a__x") { "Error: user not found" }; val b = Recording("b__y")
        val s = store()
        val plan = approved("a__x" to "{}", "b__y" to "{}").also(s::save)
        val out = PlanReplayer(ToolRegistry().register(a).register(b), s).run(plan)
        out.status shouldBe PlanStatus.FAILED
        out.results.map { it.n to it.outcome } shouldBe listOf(1 to "error", 2 to "not-run")
        out.results[0].result shouldBe "Error: user not found"
        b.calls shouldBe emptyList()
    }

    test("a thrown error is a failed step too") {
        val a = Recording("a__x") { throw IllegalStateException("boom") }
        val s = store()
        val out = PlanReplayer(ToolRegistry().register(a), s).run(approved("a__x" to "{}").also(s::save))
        out.status shouldBe PlanStatus.FAILED
        out.results.single().result shouldBe "Error: boom"
    }

    test("a plan edited after approval doesn't run") {
        val a = Recording("a__x")
        val s = store()
        val p = approved("a__x" to """{"id":"1"}""")
        val edited = p.copy(steps = p.steps.map { it.copy(args = """{"id":"2"}""") }).also(s::save)
        shouldThrow<PlanNotRunnableException> { PlanReplayer(ToolRegistry().register(a), s).run(edited) }
            .message shouldBe "This plan changed after it was approved"
        a.calls shouldBe emptyList()
    }

    test("a pending plan doesn't run") {
        val a = Recording("a__x")
        val s = store()
        val p = approved("a__x" to "{}").copy(status = PlanStatus.PENDING).also(s::save)
        shouldThrow<PlanNotRunnableException> { PlanReplayer(ToolRegistry().register(a), s).run(p) }
            .message shouldBe "This plan wasn't approved"
        a.calls shouldBe emptyList()
    }

    test("a done plan doesn't run again") {
        val a = Recording("a__x")
        val s = store()
        val replayer = PlanReplayer(ToolRegistry().register(a), s)
        val done = replayer.run(approved("a__x" to "{}").also(s::save))
        shouldThrow<PlanNotRunnableException> { replayer.run(done) }
        a.calls.size shouldBe 1
    }

    test("each replayed call is audited with its plan step") {
        val entries = mutableListOf<AuditEntry>()
        val s = store()
        PlanReplayer(ToolRegistry().register(Recording("a__x")).register(Recording("b__y")), s, audit = { entries += it })
            .run(approved("a__x" to "{}", "b__y" to "{}").also(s::save))
        entries.map { it.plan } shouldBe listOf("plan_t#1", "plan_t#2")
        entries.forEach { it.actor shouldBe "alice"; it.rule shouldBe "plan"; it.outcome shouldBe "ok" }
    }

    test("a local step takes one checkpoint first") {
        var checkpoints = 0
        val s = store()
        PlanReplayer(ToolRegistry().register(Recording("okta__x")).register(Recording("write_file", Reach.LOCAL)).register(Recording("rm", Reach.LOCAL)),
            s, checkpoint = { checkpoints++ })
            .run(approved("okta__x" to "{}", "write_file" to "{}", "rm" to "{}").also(s::save))
        checkpoints shouldBe 1
    }

    test("cancelling mid-replay saves which steps ran") {
        val started = CompletableDeferred<Unit>()
        val a = Recording("a__x")
        val b = Recording("b__y") { started.complete(Unit); awaitCancellation() }
        val c = Recording("c__z")
        val s = store()
        coroutineScope {
            val job = async { PlanReplayer(ToolRegistry().register(a).register(b).register(c), s).run(approved("a__x" to "{}", "b__y" to "{}", "c__z" to "{}").also(s::save)) }
            started.await()
            job.cancelAndJoin()
        }
        val saved = s.get("plan_t")!!
        saved.status shouldBe PlanStatus.CANCELLED
        saved.results.map { it.n to it.outcome } shouldBe listOf(1 to "ok", 2 to "cancelled", 3 to "not-run")
        c.calls shouldBe emptyList()
    }

    test("a step stopped mid-call is audited as cancelled") {
        val entries = mutableListOf<AuditEntry>()
        val started = CompletableDeferred<Unit>()
        val a = Recording("a__x") { started.complete(Unit); awaitCancellation() }
        val s = store()
        coroutineScope {
            val job = async { PlanReplayer(ToolRegistry().register(a), s, audit = { entries += it }).run(approved("a__x" to "{}").also(s::save)) }
            started.await()
            job.cancelAndJoin()
        }
        entries.single().let { it.outcome shouldBe "cancelled"; it.plan shouldBe "plan_t#1" }
    }

    test("a plan another process is starting doesn't run twice") {
        val a = Recording("a__x")
        val s = store()
        val plan = approved("a__x" to "{}").also(s::save)
        s.startLock(plan.id)!!.use {
            shouldThrow<PlanNotRunnableException> { PlanReplayer(ToolRegistry().register(a), s).run(plan) }
                .message shouldBe "This plan is starting elsewhere right now"
        }
        a.calls shouldBe emptyList()
        PlanReplayer(ToolRegistry().register(a), s).run(plan).status shouldBe PlanStatus.DONE
    }

    test("a plan whose tools aren't connected doesn't start and stays approved") {
        val a = Recording("a__x")
        val s = store()
        val plan = approved("a__x" to "{}", "gone__y" to "{}").also(s::save)
        shouldThrow<PlanNotRunnableException> { PlanReplayer(ToolRegistry().register(a), s).run(plan) }
            .message shouldBe "This plan needs tools that aren't connected here: gone__y"
        a.calls shouldBe emptyList()
        s.get("plan_t")!!.status shouldBe PlanStatus.APPROVED
    }
})
