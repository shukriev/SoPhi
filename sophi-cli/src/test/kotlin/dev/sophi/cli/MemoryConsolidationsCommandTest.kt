package dev.sophi.cli

import dev.sophi.memory.jane.ConsolidationHistoryStore
import dev.sophi.memory.jane.ConsolidationRecord
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory

class MemoryConsolidationsCommandTest : FunSpec({
    test("listing with no id shows every recorded run") {
        val path = createTempDirectory("consolidations-cli").resolve("consolidations.jsonl")
        val store = ConsolidationHistoryStore(path)
        store.record(ConsolidationRecord(100L, 1, 0, 0, 0, listOf("mem_a"), emptyList(), true))
        store.record(ConsolidationRecord(200L, 0, 1, 0, 0, emptyList(), emptyList(), true))

        val lines = mutableListOf<String>()
        renderConsolidationList(store.all(), targetId = null) { lines.add(it) }

        lines shouldHaveSize 2
    }

    test("listing with an id shows that run's full detail, including its soft-deleted ids") {
        val path = createTempDirectory("consolidations-cli").resolve("consolidations.jsonl")
        val store = ConsolidationHistoryStore(path)
        val record = ConsolidationRecord(100L, 1, 0, 0, 0, listOf("mem_a", "mem_b"), emptyList(), true)
        store.record(record)

        val lines = mutableListOf<String>()
        renderConsolidationList(store.all(), targetId = record.id) { lines.add(it) }

        lines.joinToString("\n") shouldContain "mem_a, mem_b"
    }

    test("listing with an unknown id reports not found") {
        val lines = mutableListOf<String>()
        renderConsolidationList(emptyList(), targetId = "does-not-exist") { lines.add(it) }

        lines.single() shouldContain "No consolidation run found"
    }
})
