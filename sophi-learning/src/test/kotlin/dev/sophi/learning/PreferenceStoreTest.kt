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

    test("delete reports whether a matching active record actually existed") {
        val s = store()
        s.add(rec("pref_1", 2, "negative"))
        s.delete("pref_1") shouldBe true
        s.delete("pref_1") shouldBe false   // already deleted, second call finds no active record
        s.delete("no-such-id") shouldBe false
    }

    test("link sets pairedWith to the partner's id on both records") {
        val s = store()
        s.add(rec("pref_a", 2, "negative")); s.add(rec("pref_b", 5, "positive"))
        s.link("pref_a", "pref_b")
        val bySess = s.forSession("s1").associateBy { it.id }
        bySess.getValue("pref_a").pairedWith shouldBe "pref_b"
        bySess.getValue("pref_b").pairedWith shouldBe "pref_a"
    }

    test("link no-ops silently when one side is missing") {
        val s = store()
        s.add(rec("pref_a", 2, "negative"))
        s.link("pref_a", "pref_missing")
        s.forSession("s1").single().pairedWith shouldBe "pref_missing"
    }

    test("forSession excludes a tombstoned record, same as active") {
        val s = store()
        s.add(rec("pref_1", 2, "negative"))
        s.add(rec("pref_2", 5, "positive"))
        s.delete("pref_1")
        s.forSession("s1").map { it.id } shouldBe listOf("pref_2")
    }
})
