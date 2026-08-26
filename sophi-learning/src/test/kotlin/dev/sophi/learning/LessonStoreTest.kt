package dev.sophi.learning

import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class LessonStoreTest : FunSpec({
    fun store(cap: Int = 50, versionStore: VersionStore? = null) =
        LessonStore(JsonlLog(tempdir().toPath().resolve("l.jsonl")), cap, versionStore)
    fun lesson(id: String, scope: String = "/p", use: Int = 0) =
        Lesson(id, 1L, scope, "s", "text-$id", "environment", useCount = use)

    test("fold semantics: last record per id wins") {
        val s = store()
        s.add(lesson("les_1"))
        s.archive("les_1")
        s.active("/p") shouldBe emptyList()
        s.archived("/p").single().id shouldBe "les_1"
    }

    test("add() records a Version when a VersionStore is provided") {
        val versionStore = VersionStore(tempdir().toPath().resolve("versions"))
        val s = store(versionStore = versionStore)

        s.add(lesson("les_1"))

        val history = versionStore.history(ArtifactType.LESSON, "les_1")
        history shouldHaveSize 1
        history.first().producedBy shouldBe ProducedBy.REFLECTION
    }

    test("archive() records a new Version reflecting the archived state") {
        val versionStore = VersionStore(tempdir().toPath().resolve("versions"))
        val s = store(versionStore = versionStore)
        s.add(lesson("les_1"))

        s.archive("les_1")

        versionStore.history(ArtifactType.LESSON, "les_1") shouldHaveSize 2
    }

    test("bumpUse() records a new Version per bumped lesson") {
        val versionStore = VersionStore(tempdir().toPath().resolve("versions"))
        val s = store(versionStore = versionStore)
        s.add(lesson("les_1"))

        s.bumpUse(listOf(lesson("les_1")))

        versionStore.history(ArtifactType.LESSON, "les_1") shouldHaveSize 2
    }

    test("LessonStore works exactly as before when no VersionStore is provided") {
        val s = store()

        s.add(lesson("les_1")) // must not throw

        s.active("/p") shouldHaveSize 1
    }

    test("archive reports whether a matching lesson actually existed") {
        val s = store()
        s.add(lesson("les_1"))
        s.archive("les_1") shouldBe true
        s.archive("les_1") shouldBe false   // already archived, second call finds no active record
        s.archive("no-such-id") shouldBe false
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
