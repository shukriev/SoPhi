package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.schedule.model.Proposal
import dev.sophi.schedule.store.ProposalStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.mockk.mockk

private const val TEST_CONTEXT_WINDOW = 100_000

class SophiRuntimeProposalsTest : FunSpec({
    test("proposals() reads from the schedule dir set via schedule()") {
        val scheduleDir = tempdir().toPath()
        val store = ProposalStore(scheduleDir.resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).schedule(scheduleDir).build()

        runtime.proposals().map { it.id } shouldBe listOf(p.id)
    }

    test("proposals(status) filters, matching ProposalStore.list") {
        val scheduleDir = tempdir().toPath()
        val store = ProposalStore(scheduleDir.resolve("proposals.jsonl"))
        store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).schedule(scheduleDir).build()

        runtime.proposals("accepted") shouldBe emptyList()
        runtime.proposals("pending").size shouldBe 1
    }

    test("acceptProposal transitions a pending proposal") {
        val scheduleDir = tempdir().toPath()
        val store = ProposalStore(scheduleDir.resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).schedule(scheduleDir).build()

        runtime.acceptProposal(p.id) shouldBe true
        store.get(p.id)!!.status shouldBe "accepted"
    }

    test("rejectProposal transitions a pending proposal with a reason") {
        val scheduleDir = tempdir().toPath()
        val store = ProposalStore(scheduleDir.resolve("proposals.jsonl"))
        val p = store.add(Proposal(sessionId = "s1", title = "A", category = "process", rationale = "r", suggestedAction = "x"))
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).schedule(scheduleDir).build()

        runtime.rejectProposal(p.id, "no thanks") shouldBe true
        store.get(p.id)!!.reviewReason shouldBe "no thanks"
    }

    test("without schedule(), proposals() doesn't throw -- falls back to the default schedule dir") {
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).build()

        runtime.proposals()
    }
})
