package dev.sophi.learning

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldContain

class LessonRecallTest : FunSpec({
    fun fixture(): Pair<LessonStore, LessonsSection> {
        val store = LessonStore(JsonlLog(tempdir().toPath().resolve("l.jsonl")))
        val config = LearningConfig(home = java.nio.file.Path.of("/tmp"), scope = "/p")
        return store to LessonsSection(RecencyUsageRecall(store), store, config)
    }

    test("preference lessons rank first; local before global; useCount breaks ties") {
        val (store, _) = fixture()
        store.add(Lesson("les_g", 3L, "*", "s", "global tip", "approach", useCount = 9))
        store.add(Lesson("les_l", 2L, "/p", "s", "local tip", "environment", useCount = 1))
        store.add(Lesson("les_p", 1L, "/p", "s", "user prefers X", "preference"))
        val order = RecencyUsageRecall(store).recall("/p", budgetTokens = 600).map { it.id }
        order shouldBe listOf("les_p", "les_l", "les_g")
    }

    test("an oversized lesson is skipped, not a hard stop — smaller lessons ranked after it still fit") {
        val (store, _) = fixture()
        // les_a ranks before les_b (higher useCount) but alone consumes the whole budget.
        store.add(Lesson("les_a", 1L, "/p", "s", "x".repeat(500), "approach", useCount = 5))
        store.add(Lesson("les_b", 2L, "/p", "s", "short tip", "approach", useCount = 1))
        val recalled = RecencyUsageRecall(store, maxRecalled = 10).recall("/p", budgetTokens = 100)
        recalled.map { it.id } shouldBe listOf("les_b")
    }

    test("maxRecalled still caps the count once reached, independent of remaining budget") {
        val (store, _) = fixture()
        store.add(Lesson("les_a", 1L, "/p", "s", "a", "approach", useCount = 3))
        store.add(Lesson("les_b", 2L, "/p", "s", "b", "approach", useCount = 2))
        store.add(Lesson("les_c", 3L, "/p", "s", "c", "approach", useCount = 1))
        val recalled = RecencyUsageRecall(store, maxRecalled = 2).recall("/p", budgetTokens = 600)
        recalled.size shouldBe 2
    }

    test("render produces section and bumps useCount; empty store renders null") {
        val (store, section) = fixture()
        section.render("/p").shouldBeNull()
        store.add(Lesson("les_1", 1L, "/p", "s", "Use -pl targeting", "environment"))
        section.render("/p")!! shouldContain "## Lessons from previous sessions"
        store.active("/p").single().useCount shouldBe 1
    }

    test("render truncates an oversized lesson so it can't dominate the injected prompt") {
        val (store, section) = fixture()
        store.add(Lesson("les_1", 1L, "/p", "s", "x".repeat(1000), "environment"))
        val rendered = section.render("/p")!!
        rendered.length shouldBeLessThan 500
    }
})
