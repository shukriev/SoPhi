package dev.sophi.skills

import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class SkillVersionStoreTest : FunSpec({
    fun store(project: Boolean = false): SkillVersionStore =
        SkillVersionStore(VersionStore(createTempDirectory("skill-version-store")), project)

    test("record then get round-trips the full version, including trial default") {
        val store = store()
        val recorded = store.record(
            SkillVersion(skillId = "site-example-com", project = false, content = "---\ntitle: t\n---\nbody")
        )

        val fetched = store.get(recorded.id)

        fetched shouldBe recorded
        fetched?.trial shouldBe false
    }

    test("history returns newest first and filters by skillId") {
        val store = store()
        val v1 = store.record(SkillVersion(skillId = "site-a", project = false, content = "one"))
        val v2 = store.record(SkillVersion(skillId = "site-a", project = false, content = "two"))
        store.record(SkillVersion(skillId = "site-b", project = false, content = "other skill"))

        val history = store.history("site-a", project = false)

        history.map { it.id } shouldBe listOf(v2.id, v1.id)
        history shouldHaveSize 2
    }

    test("get returns null for an unknown id") {
        store().get("does-not-exist") shouldBe null
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

    test("record() persists through the shared VersionStore, not private in-memory state") {
        val home = createTempDirectory("skill-version-store")
        val versionStore = VersionStore(home)
        val recorded = SkillVersionStore(versionStore, project = false).record(
            SkillVersion(skillId = "greet", project = false, content = "hello")
        )

        // A second, independent SkillVersionStore over the same VersionStore home proves this
        // went through shared persistent storage, not an object held only in memory.
        val fromSecondInstance = SkillVersionStore(VersionStore(home), project = false).get(recorded.id)

        fromSecondInstance shouldBe recorded
    }

    test("a legacyJsonlPath is transparently migrated on first read, with no separate migration step") {
        val dir = createTempDirectory("skill-version-store")
        val legacyFile = dir.resolve(".versions.jsonl")
        legacyFile.writeText(
            kotlinx.serialization.json.Json { encodeDefaults = true }
                .encodeToString(SkillVersion.serializer(), SkillVersion(ts = 100, skillId = "greet", project = false, content = "legacy content")) + "\n"
        )
        val store = SkillVersionStore(VersionStore(dir.resolve("versions")), project = false, legacyJsonlPath = legacyFile)

        val history = store.history("greet", project = false)

        history shouldHaveSize 1
        history.first().content shouldBe "legacy content"
    }

    test("versions carry a project flag matching the store's own scope") {
        val projectStore = store(project = true)
        val recorded = projectStore.record(SkillVersion(skillId = "site-a", project = true, content = "one"))

        recorded.project shouldBe true
        projectStore.get(recorded.id)?.project shouldBe true
    }
})
