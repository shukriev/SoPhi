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

    test("failures that repeat an identical earlier call are counted apart") {
        val stats = ToolStatsStore(log(
            ev("fetch_url", false), ev("fetch_url", false).copy(repeat = 2),
            ev("fetch_url", false).copy(repeat = 3), ev("fetch_url", true).copy(repeat = 4)
        ), ttlMillis = 0).stats("/p").getValue("fetch_url")
        stats.failures shouldBe 3
        stats.repeatedFailures shouldBe 2
    }

    test("events written before repeat was recorded read as first calls") {
        val l = JsonlLog(tempdir().toPath().resolve("e.jsonl"))
        l.append("""{"ts":1,"scope":"/p","sessionId":"s","tool":"grep","success":false,"durationMillis":1}""")
        ToolStatsStore(l, ttlMillis = 0).stats("/p").getValue("grep").repeatedFailures shouldBe 0
    }

    test("torn lines are skipped, not fatal") {
        val l = log(ev("grep", true)); l.append("{not json")
        ToolStatsStore(l, ttlMillis = 0).stats("/p").getValue("grep").attempts shouldBe 1
    }

    test("a stats call within the TTL window is served from cache, not rebuilt from the log") {
        val l = log(ev("grep", true))
        val store = ToolStatsStore(l, ttlMillis = 60_000)
        store.stats("/p").getValue("grep").attempts shouldBe 1

        // Append directly to the underlying log, bypassing the store — a rebuild would see this.
        l.append(Json.encodeToString(ToolEvent.serializer(), ev("grep", true)))
        store.stats("/p").getValue("grep").attempts shouldBe 1   // still the cached value
    }
})
