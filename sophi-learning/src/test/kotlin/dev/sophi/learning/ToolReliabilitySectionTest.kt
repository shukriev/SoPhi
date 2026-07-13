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

    test("a streak of 3+ failures renders even when the overall failure rate is below threshold") {
        // 7 successes then 3 failures: failure rate 3/10 = 0.3 < reliabilityFailureRate (0.5),
        // but the trailing streak (>=3) must independently trigger the warning.
        val pairs = (Array(7) { "flaky" to true } + Array(3) { "flaky" to false })
        val text = ToolReliabilitySection(store(*pairs), config).render("/p")!!
        text shouldContain "flaky"
    }

    test("multiple failing tools are all listed in one render") {
        val pairs = Array(5) { "fetch_url" to false } + Array(5) { "bash" to false }
        val text = ToolReliabilitySection(store(*pairs), config).render("/p")!!
        text shouldContain "fetch_url"
        text shouldContain "bash"
    }

    test("render(scope) is driven by its own argument, not silently by config.scope") {
        val configuredForOtherScope = LearningConfig(home = java.nio.file.Path.of("/tmp"), scope = "/configured")
        val s = store(*(Array(5) { "fetch_url" to false }))
        ToolReliabilitySection(s, configuredForOtherScope).render("/p")!! shouldContain "fetch_url"
    }
})
