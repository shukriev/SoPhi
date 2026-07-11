package dev.sophi.cli

import dev.sophi.learning.JsonlLog
import dev.sophi.learning.PreferenceRecord
import dev.sophi.learning.PreferenceStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.engine.spec.tempdir

class FeedbackCommandTest : FunSpec({
    test("list renders feedback records; delete removes from active") {
        val home = tempdir().toPath()
        val store = PreferenceStore(JsonlLog(home.resolve("preferences.jsonl")))
        val scope = System.getProperty("user.dir")
        store.add(PreferenceRecord(
            id = "pref_1",
            ts = 1L,
            scope = scope,
            sessionId = "sess_1",
            entryIndex = 0,
            polarity = "positive",
            source = "explicit",
            reason = "worked well",
            weight = 1.0
        ))

        val out = StringBuilder()
        FeedbackList(home) { out.appendLine(it) }.run()
        out.toString() shouldContain "pref_1"
        out.toString() shouldContain "positive"

        FeedbackDelete(home, "pref_1") {}.run()
        store.active(scope) shouldBe emptyList<PreferenceRecord>()
    }
})
