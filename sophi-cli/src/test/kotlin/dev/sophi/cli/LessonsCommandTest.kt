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

    test("list honors an explicit --scope instead of always using the working directory") {
        val home = tempdir().toPath()
        val store = LessonStore(JsonlLog(home.resolve("lessons.jsonl")))
        store.add(Lesson("les_other", 1L, "/some/other/project", "s", "other-project lesson", "approach"))

        val out = StringBuilder()
        LessonsList(home, scope = "/some/other/project") { out.appendLine(it) }.run()
        out.toString() shouldContain "les_other"
    }

    test("archive reports when the given id doesn't match any active lesson") {
        val home = tempdir().toPath()
        val out = StringBuilder()
        LessonsArchive(home, "no-such-id") { out.appendLine(it) }.run()
        out.toString() shouldContain "No active lesson"
    }

    test("--all merges global (scope=*) lessons in with the project's own") {
        val home = tempdir().toPath()
        val store = LessonStore(JsonlLog(home.resolve("lessons.jsonl")))
        store.add(Lesson("les_global", 1L, "*", "s", "applies everywhere", "approach"))

        val withoutAll = StringBuilder()
        LessonsList(home) { withoutAll.appendLine(it) }.run(all = false)
        withoutAll.toString() shouldContain "No lessons."

        val withAll = StringBuilder()
        LessonsList(home) { withAll.appendLine(it) }.run(all = true)
        withAll.toString() shouldContain "les_global"
    }
})
