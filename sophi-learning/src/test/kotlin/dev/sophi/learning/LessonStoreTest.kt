package dev.sophi.learning

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

class LessonStoreTest : FunSpec({
    fun store(cap: Int = 50) = LessonStore(JsonlLog(tempdir().toPath().resolve("l.jsonl")), cap)
    fun lesson(id: String, scope: String = "/p", use: Int = 0) =
        Lesson(id, 1L, scope, "s", "text-$id", "environment", useCount = use)

    test("fold semantics: last record per id wins") {
        val s = store()
        s.add(lesson("les_1"))
        s.archive("les_1")
        s.active("/p") shouldBe emptyList()
        s.archived("/p").single().id shouldBe "les_1"
    }

    test("bumpUse increments useCount via appended record") {
        val s = store()
        s.add(lesson("les_1"))
        s.bumpUse(s.active("/p"))
        s.active("/p").single().useCount shouldBe 1
    }

    test("cap evicts lowest useCount then oldest") {
        val s = store(cap = 2)
        s.add(lesson("les_a", use = 5)); s.add(lesson("les_b", use = 1)); s.add(lesson("les_c", use = 3))
        s.active("/p").map { it.id }.sorted() shouldBe listOf("les_a", "les_c")
    }

    test("activeIncludingGlobal merges scope and star") {
        val s = store()
        s.add(lesson("les_p", scope = "/p")); s.add(lesson("les_g", scope = "*"))
        s.activeIncludingGlobal("/p").map { it.id }.sorted() shouldBe listOf("les_g", "les_p")
    }
})
