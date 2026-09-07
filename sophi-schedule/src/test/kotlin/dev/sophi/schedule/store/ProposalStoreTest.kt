package dev.sophi.schedule.store

import dev.sophi.schedule.model.Proposal
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ProposalStoreTest : FunSpec({
    test("list returns proposals most recent first") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        val a = store.add(Proposal(ts = 100L, sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))
        val b = store.add(Proposal(ts = 200L, sessionId = "s1", title = "B", category = "process", rationale = "r", suggestedAction = "x"))

        store.list().map { it.id } shouldBe listOf(b.id, a.id)
    }

    test("list(status) filters; new proposals default to pending") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))

        store.list("pending").map { it.id } shouldBe listOf(p.id)
        store.list("accepted") shouldBe emptyList()
    }

    test("accept transitions a pending proposal and records reviewedAtMs") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))

        store.accept(p.id) shouldBe true
        val updated = store.get(p.id)!!
        updated.status shouldBe "accepted"
        updated.reviewedAtMs.shouldNotBeNull()
    }

    test("reject transitions a pending proposal and records the reason") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))

        store.reject(p.id, "not evidence-backed") shouldBe true
        val updated = store.get(p.id)!!
        updated.status shouldBe "rejected"
        updated.reviewReason shouldBe "not evidence-backed"
    }

    test("reject on an already-accepted proposal fails, leaving it unchanged") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))
        store.accept(p.id)

        store.reject(p.id, "changed my mind") shouldBe false
        store.get(p.id)!!.status shouldBe "accepted"
    }

    test("accept on an unknown id returns false") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))

        store.accept("no-such-id") shouldBe false
    }

    test("rejected proposals are never deleted — they remain in list() with status rejected") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))
        store.reject(p.id, "no")

        store.list() shouldHaveSize 1
        store.list().single().status shouldBe "rejected"
    }

    test("concurrent accept and reject on the same proposal never let both succeed") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()

        val t1 = Thread { results.add(store.accept(p.id)) }
        val t2 = Thread { results.add(store.reject(p.id, "no")) }
        t1.start(); t2.start()
        t1.join(); t2.join()

        results.count { it } shouldBe 1
    }

    test("markPrOpened transitions an accepted proposal and records the PR url") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))
        store.accept(p.id)

        store.markPrOpened(p.id, "https://github.com/shukriev/SoPhi/pull/1") shouldBe true
        val updated = store.get(p.id)!!
        updated.status shouldBe "pr-opened"
        updated.implementedDetail shouldBe "https://github.com/shukriev/SoPhi/pull/1"
        updated.implementedAtMs.shouldNotBeNull()
    }

    test("markImplementationFailed transitions an accepted proposal and records the reason") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))
        store.accept(p.id)

        store.markImplementationFailed(p.id, "build failed") shouldBe true
        val updated = store.get(p.id)!!
        updated.status shouldBe "implementation-failed"
        updated.implementedDetail shouldBe "build failed"
    }

    test("markPrOpened on a pending (not yet accepted) proposal fails, leaving it unchanged") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))

        store.markPrOpened(p.id, "https://example.invalid/pull/1") shouldBe false
        store.get(p.id)!!.status shouldBe "pending"
    }

    test("markImplementationFailed on an unknown id returns false") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))

        store.markImplementationFailed("no-such-id", "whatever") shouldBe false
    }
})
