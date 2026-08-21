package dev.sophi.schedule.tools

import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.schedule.store.ProposalStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class ProposalPluginTest : FunSpec({
    fun hook(store: ProposalStore) = ProposalPlugin(store).hooks().single { it.point == HookPoint.BEFORE_TOOL }

    test("BEFORE_TOOL for propose_improvement persists a Proposal keyed by the dispatch's sessionId") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        runBlocking {
            hook(store).invoke(HookContext(
                sessionId = "s1", toolName = "propose_improvement",
                argumentsJson = """{"title":"Prefer X over Y","category":"tool-reliability","rationale":"tool Y fails 40% of the time per tool-events.jsonl","suggestedAction":"deprecate Y"}"""
            ))
        }
        val proposals = store.list()
        proposals shouldHaveSize 1
        proposals.single().sessionId shouldBe "s1"
        proposals.single().title shouldBe "Prefer X over Y"
        proposals.single().category shouldBe "tool-reliability"
    }

    test("BEFORE_TOOL for any other tool name is a no-op") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        runBlocking {
            hook(store).invoke(HookContext(sessionId = "s1", toolName = "read_file", argumentsJson = """{"path":"x"}"""))
        }
        store.list() shouldBe emptyList()
    }

    test("malformed argumentsJson does not throw and persists nothing") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        runBlocking {
            hook(store).invoke(HookContext(sessionId = "s1", toolName = "propose_improvement", argumentsJson = "not json"))
        }
        store.list() shouldBe emptyList()
    }

    test("a null argumentsJson does not throw and persists nothing") {
        val store = ProposalStore(tempdir().toPath().resolve("proposals.jsonl"))
        runBlocking {
            hook(store).invoke(HookContext(sessionId = "s1", toolName = "propose_improvement", argumentsJson = null))
        }
        store.list() shouldBe emptyList()
    }
})
