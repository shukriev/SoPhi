package dev.sophi.learning

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
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

    test("render produces section and bumps useCount; empty store renders null") {
        val (store, section) = fixture()
        section.render("/p").shouldBeNull()
        store.add(Lesson("les_1", 1L, "/p", "s", "Use -pl targeting", "environment"))
        section.render("/p")!! shouldContain "## Lessons from previous sessions"
        store.active("/p").single().useCount shouldBe 1
    }
})
