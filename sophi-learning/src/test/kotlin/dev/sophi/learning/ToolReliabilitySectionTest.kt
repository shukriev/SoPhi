package dev.sophi.learning

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

class ToolReliabilitySectionTest : FunSpec({
    fun store(vararg pairs: Pair<String, Boolean>): ToolStatsStore {
        val log = JsonlLog(tempdir().toPath().resolve("e.jsonl"))
        pairs.forEach { (tool, ok) ->
            log.append(Json.encodeToString(ToolEvent.serializer(),
                ToolEvent(1, "/p", "s", tool, ok, 10, if (ok) null else "Error: timeout")))
        }
        return ToolStatsStore(log, ttlMillis = 0)
    }
    val config = LearningConfig(home = java.nio.file.Path.of("/tmp"), scope = "/p")

    test("failing tool above thresholds renders a warning with the last error") {
        val s = store(*(Array(5) { "fetch_url" to false }))
        val text = ToolReliabilitySection(s, config).render("/p")!!
        text shouldContain "## Tool reliability notes"
        text shouldContain "fetch_url"
        text shouldContain "Error: timeout"
    }

    test("healthy tools render null") {
        ToolReliabilitySection(store(*(Array(10) { "grep" to true })), config).render("/p").shouldBeNull()
    }

    test("below min attempts renders null even when failing") {
        ToolReliabilitySection(store(*(Array(3) { "x" to false })), config).render("/p").shouldBeNull()
    }
})
