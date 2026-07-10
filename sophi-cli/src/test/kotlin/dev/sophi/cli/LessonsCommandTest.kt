package dev.sophi.cli

import dev.sophi.learning.JsonlLog
import dev.sophi.learning.Lesson
import dev.sophi.learning.LessonStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.engine.spec.tempdir

class LessonsCommandTest : FunSpec({
    test("list renders lessons; archive removes from active") {
        val home = tempdir().toPath()
        val store = LessonStore(JsonlLog(home.resolve("lessons.jsonl")))
        store.add(Lesson("les_1", 1L, System.getProperty("user.dir"), "s", "remember X", "approach"))

        val out = StringBuilder()
        LessonsList(home) { out.appendLine(it) }.run()
        out.toString() shouldContain "les_1"
        out.toString() shouldContain "remember X"

        LessonsArchive(home, "les_1") {}.run()
        store.active(System.getProperty("user.dir")) shouldBe emptyList<Lesson>()
    }
})
