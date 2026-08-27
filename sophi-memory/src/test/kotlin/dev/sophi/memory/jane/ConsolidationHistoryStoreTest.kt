package dev.sophi.memory.jane

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

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

    test("a JSONL line written before the id field existed still deserializes, with a generated id") {
        val path = createTempDirectory("consolidation-history").resolve("consolidations.jsonl")
        path.writeText(
            """{"ts":100,"merged":1,"strengthened":0,"compressed":0,"pruned":0,"softDeletedIds":["mem_a"],"purgedIds":[],"autoPurgeEnabled":true}""" + "\n"
        )

        val records = ConsolidationHistoryStore(path).all()

        records shouldHaveSize 1
        records.single().id.isNotBlank() shouldBe true
        records.single().ts shouldBe 100L
    }
})
