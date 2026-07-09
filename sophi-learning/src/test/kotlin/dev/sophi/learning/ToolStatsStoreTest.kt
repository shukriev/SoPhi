package dev.sophi.learning

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class ToolStatsStoreTest : FunSpec({
    fun log(vararg events: ToolEvent): JsonlLog {
        val log = JsonlLog(tempdir().toPath().resolve("e.jsonl"))
        events.forEach { log.append(Json.encodeToString(ToolEvent.serializer(), it)) }
        return log
    }
    fun ev(tool: String, ok: Boolean, scope: String = "/p", dur: Long = 10) =
        ToolEvent(1L, scope, "s", tool, ok, dur, if (ok) null else "Error: x")

    test("aggregates attempts, failures, streak, mean duration, last errors per tool+scope") {
        val store = ToolStatsStore(log(
            ev("grep", true, dur = 10), ev("grep", false, dur = 30),
            ev("grep", false, dur = 20), ev("bash", true, scope = "/other")
        ), ttlMillis = 0)
        val stats = store.stats("/p").getValue("grep")
        stats.attempts shouldBe 3
        stats.failures shouldBe 2
        stats.streak shouldBe 2               // two consecutive failures at the tail
        stats.meanDurationMillis shouldBe 20L
        stats.lastErrors shouldBe listOf("Error: x", "Error: x")
        store.stats("/p").containsKey("bash") shouldBe false
    }

    test("torn lines are skipped, not fatal") {
        val l = log(ev("grep", true)); l.append("{not json")
        ToolStatsStore(l, ttlMillis = 0).stats("/p").getValue("grep").attempts shouldBe 1
    }
})
