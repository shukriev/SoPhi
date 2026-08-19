package dev.sophi.cli

import dev.sophi.schedule.model.Proposal
import dev.sophi.schedule.store.ProposalStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ProposalsCommandTest : FunSpec({
    test("ProposalsList renders id, status, category, and title") {
        val home = tempdir().toPath()
        val store = ProposalStore(home.resolve("proposals.jsonl"))
        store.add(Proposal(sessionId = "s1", title = "Deprecate tool Y", category = "tool-reliability", rationale = "r", suggestedAction = "a"))
        val out = StringBuilder()
        ProposalsList(home, null) { out.appendLine(it) }.run()
        out.toString() shouldContain "Deprecate tool Y"
        out.toString() shouldContain "pending"
        out.toString() shouldContain "tool-reliability"
    }

    test("ProposalsList reports when there are no proposals") {
        val home = tempdir().toPath()
        val out = StringBuilder()
        ProposalsList(home, null) { out.appendLine(it) }.run()
        out.toString() shouldContain "No proposals"
    }

    test("ProposalsList filters by status") {
        val home = tempdir().toPath()
        val store = ProposalStore(home.resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "other", rationale = "r", suggestedAction = "a"))
        store.accept(p.id)
        val out = StringBuilder()
        ProposalsList(home, "rejected") { out.appendLine(it) }.run()
        out.toString() shouldContain "No proposals"
    }

    test("ProposalsAccept accepts a pending proposal; reports failure for unknown id") {
        val home = tempdir().toPath()
        val store = ProposalStore(home.resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "other", rationale = "r", suggestedAction = "a"))
        val out = StringBuilder()
        ProposalsAccept(home, p.id) { out.appendLine(it) }.run()
        store.get(p.id)!!.status shouldBe "accepted"
        val out2 = StringBuilder()
        ProposalsAccept(home, "no-such-id") { out2.appendLine(it) }.run()
        out2.toString() shouldContain "No pending proposal found"
    }

    test("ProposalsReject rejects a pending proposal with a reason; reports failure for unknown id") {
        val home = tempdir().toPath()
        val store = ProposalStore(home.resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "other", rationale = "r", suggestedAction = "a"))
        val out = StringBuilder()
        ProposalsReject(home, p.id, "not evidence-backed") { out.appendLine(it) }.run()
        store.get(p.id)!!.status shouldBe "rejected"
        store.get(p.id)!!.reviewReason shouldBe "not evidence-backed"
        val out2 = StringBuilder()
        ProposalsReject(home, "no-such-id", "x") { out2.appendLine(it) }.run()
        out2.toString() shouldContain "No pending proposal found"
    }
})
