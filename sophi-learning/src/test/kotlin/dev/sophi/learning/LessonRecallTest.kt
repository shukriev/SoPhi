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

    test("SemanticRecall ranks by query similarity, not recency") {
        val store = LessonStore(JsonlLog(tempdir().toPath().resolve("l.jsonl")))
        // Recency/usage would rank les_recent first (higher ts, higher useCount); semantic
        // similarity to "database migration" must still put les_relevant on top.
        store.add(Lesson("les_recent", 10L, "/p", "s", "always run tests before committing", "approach", useCount = 5))
        store.add(Lesson("les_relevant", 1L, "/p", "s", "database migrations need a rollback plan", "approach", useCount = 0))
        val recall = SemanticRecall(FakeEmbeddingProvider(), store)
        val order = recall.recall("/p", budgetTokens = 600, query = "planning a database migration").map { it.id }
        order.first() shouldBe "les_relevant"
    }

    test("SemanticRecall with a null query falls back to recency/usage ranking") {
        val store = LessonStore(JsonlLog(tempdir().toPath().resolve("l.jsonl")))
        store.add(Lesson("les_a", 1L, "/p", "s", "a tip", "approach", useCount = 1))
        store.add(Lesson("les_b", 2L, "/p", "s", "b tip", "approach", useCount = 5))
        val semantic = SemanticRecall(FakeEmbeddingProvider(), store).recall("/p", budgetTokens = 600, query = null)
        val recency = RecencyUsageRecall(store).recall("/p", budgetTokens = 600, query = null)
        semantic.map { it.id } shouldBe recency.map { it.id }
    }

    test("SemanticRecall caches lesson embeddings across calls (embed is not re-invoked per lesson per call)") {
        val store = LessonStore(JsonlLog(tempdir().toPath().resolve("l.jsonl")))
        store.add(Lesson("les_a", 1L, "/p", "s", "a tip about testing", "approach"))
        var embedCalls = 0
        val counting = object : dev.sophi.ai.api.EmbeddingProvider {
            override val dimensions = 64
            override suspend fun embed(texts: List<String>): List<FloatArray> {
                embedCalls += texts.size
                return FakeEmbeddingProvider().embed(texts)
            }
        }
        val recall = SemanticRecall(counting, store)
        recall.recall("/p", budgetTokens = 600, query = "testing")
        val afterFirst = embedCalls
        recall.recall("/p", budgetTokens = 600, query = "testing again")
        // Second call embeds the new query (+1) but must not re-embed the already-cached lesson.
        embedCalls shouldBe afterFirst + 1
    }

    test("SemanticRecall respects maxRecalled and budget the same way RecencyUsageRecall does") {
        val store = LessonStore(JsonlLog(tempdir().toPath().resolve("l.jsonl")))
        store.add(Lesson("les_a", 1L, "/p", "s", "x".repeat(500), "approach"))
        store.add(Lesson("les_b", 2L, "/p", "s", "short tip", "approach"))
        val recalled = SemanticRecall(FakeEmbeddingProvider(), store, maxRecalled = 10)
            .recall("/p", budgetTokens = 100, query = "tip")
        recalled.map { it.id } shouldBe listOf("les_b")
    }

    test("LessonsSection.render threads a query through to the recall implementation") {
        val store = LessonStore(JsonlLog(tempdir().toPath().resolve("l.jsonl")))
        store.add(Lesson("les_a", 1L, "/p", "s", "old but recent-ranked tip", "approach", useCount = 5))
        store.add(Lesson("les_b", 2L, "/p", "s", "database rollback plan", "approach", useCount = 0))
        val config = LearningConfig(home = java.nio.file.Path.of("/tmp"), scope = "/p")
        val section = LessonsSection(SemanticRecall(FakeEmbeddingProvider(), store), store, config)
        val rendered = section.render("/p", query = "database rollback")!!
        rendered shouldContain "database rollback plan"
    }
})
