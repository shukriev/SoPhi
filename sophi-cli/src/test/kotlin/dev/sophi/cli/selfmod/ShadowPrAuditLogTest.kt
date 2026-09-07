package dev.sophi.cli.selfmod

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

class ShadowPrAuditLogTest : FunSpec({
    test("record then all round-trips an entry") {
        val log = ShadowPrAuditLog(tempdir().toPath().resolve("shadow-pr-audit.jsonl"))
        val entry = ShadowPrAuditEntry(
            proposalId = "prop_1", worktreePath = "/tmp/x", branch = "selfmod/prop_1-x",
            decision = "pr-opened", reason = "all gates passed", prUrl = "https://example.invalid/pull/1"
        )

        log.record(entry)

        log.all() shouldBe listOf(entry)
    }

    test("all returns entries in append order across multiple records") {
        val log = ShadowPrAuditLog(tempdir().toPath().resolve("shadow-pr-audit.jsonl"))
        val e1 = ShadowPrAuditEntry(proposalId = "p1", worktreePath = "/a", branch = "b1", decision = "implementation-failed", reason = "build failed")
        val e2 = ShadowPrAuditEntry(proposalId = "p2", worktreePath = "/b", branch = "b2", decision = "pr-opened", reason = "ok", prUrl = "https://example.invalid/pull/2")

        log.record(e1)
        log.record(e2)

        log.all().map { it.proposalId } shouldBe listOf("p1", "p2")
    }

    test("all on a nonexistent file returns an empty list") {
        val log = ShadowPrAuditLog(tempdir().toPath().resolve("does-not-exist.jsonl"))

        log.all() shouldBe emptyList()
    }
})
