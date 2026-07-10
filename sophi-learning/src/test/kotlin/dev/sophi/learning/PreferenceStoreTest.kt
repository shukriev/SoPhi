package dev.sophi.learning

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

class PreferenceStoreTest : FunSpec({
    fun store() = PreferenceStore(JsonlLog(tempdir().toPath().resolve("p.jsonl")))
    fun rec(id: String, entry: Int, polarity: String, session: String = "s1") = PreferenceRecord(
        id, 1L, "/p", session, entry, polarity, "explicit", reason = "r")

    test("add/active/delete round-trip with tombstone") {
        val s = store()
        s.add(rec("pref_1", 2, "negative"))
        s.active("/p").single().id shouldBe "pref_1"
        s.delete("pref_1")
        s.active("/p") shouldBe emptyList()
    }

    test("link sets pairedWith on both records") {
        val s = store()
        s.add(rec("pref_a", 2, "negative")); s.add(rec("pref_b", 5, "positive"))
        s.link("s1", negativeEntryIndex = 2, positiveEntryIndex = 5)
        val bySess = s.forSession("s1").associateBy { it.id }
        bySess.getValue("pref_a").pairedWith shouldBe 5
        bySess.getValue("pref_b").pairedWith shouldBe 2
    }
})
