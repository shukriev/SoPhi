package dev.sophi.memory.jane

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class ConsolidationHistoryStoreTest : FunSpec({
    fun store(): ConsolidationHistoryStore = ConsolidationHistoryStore(createTempDirectory("consolidation-history").resolve("consolidations.jsonl"))

    test("record then all() round-trips a full entry") {
        val store = store()
        val entry = ConsolidationRecord(
            ts = 100L, merged = 1, strengthened = 2, compressed = 1, pruned = 0,
            softDeletedIds = listOf("mem_a", "mem_b"), purgedIds = listOf("mem_c"),
            autoPurgeEnabled = true
        )

        store.record(entry)

        store.all() shouldBe listOf(entry)
    }

    test("all() returns an empty list when no consolidation has ever run") {
        store().all() shouldBe emptyList()
    }

    test("multiple runs append in order") {
        val store = store()
        val first = ConsolidationRecord(100L, 0, 0, 0, 0, emptyList(), emptyList(), true)
        val second = ConsolidationRecord(200L, 0, 0, 0, 0, emptyList(), emptyList(), false)

        store.record(first)
        store.record(second)

        store.all().map { it.ts } shouldBe listOf(100L, 200L)
    }
})
