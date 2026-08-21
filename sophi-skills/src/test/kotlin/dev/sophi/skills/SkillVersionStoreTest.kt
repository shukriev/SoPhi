package dev.sophi.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class SkillVersionStoreTest : FunSpec({
    fun store(): SkillVersionStore = SkillVersionStore(createTempDirectory("skill-version-store").resolve(".versions.jsonl"))

    test("record then get round-trips the full version, including trial default") {
        val store = store()
        val recorded = store.record(
            SkillVersion(skillId = "site-example-com", project = false, content = "---\ntitle: t\n---\nbody")
        )

        val fetched = store.get(recorded.id)

        fetched shouldBe recorded
        fetched?.trial shouldBe false
    }

    test("history returns newest first and filters by skillId and project") {
        val store = store()
        val v1 = store.record(SkillVersion(skillId = "site-a", project = false, content = "one"))
        val v2 = store.record(SkillVersion(skillId = "site-a", project = false, content = "two"))
        store.record(SkillVersion(skillId = "site-b", project = false, content = "other skill"))
        store.record(SkillVersion(skillId = "site-a", project = true, content = "project-scoped"))

        val history = store.history("site-a", project = false)

        history.map { it.id } shouldBe listOf(v2.id, v1.id)
        history shouldHaveSize 2
    }

    test("get returns null for an unknown id") {
        store().get("skillver_does-not-exist") shouldBe null
    }

    test("history is empty for a skillId with no recorded versions") {
        store().history("site-never-written", project = false) shouldHaveSize 0
    }

    test("all() returns every recorded version across every skill id, not filtered by any one id") {
        val store = store()
        val a = store.record(SkillVersion(skillId = "site-a", project = false, content = "one"))
        val b = store.record(SkillVersion(skillId = "site-b", project = false, content = "two"))

        store.all().map { it.id }.toSet() shouldBe setOf(a.id, b.id)
    }
})
