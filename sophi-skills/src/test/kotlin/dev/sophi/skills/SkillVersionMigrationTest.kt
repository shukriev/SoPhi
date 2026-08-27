package dev.sophi.skills

import dev.sophi.versioning.ArtifactType
import dev.sophi.versioning.ProducedBy
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class SkillVersionMigrationTest : FunSpec({
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    test("migrateSkillVersions copies every JSONL entry into VersionStore, grouped by skillId, oldest first") {
        val dir = createTempDirectory("skill-migration-test")
        val oldFile = dir.resolve(".versions.jsonl")
        oldFile.writeText(
            json.encodeToString(SkillVersion(ts = 100, skillId = "greet", project = false, content = "v1")) + "\n" +
                json.encodeToString(SkillVersion(ts = 200, skillId = "greet", project = false, content = "v2")) + "\n" +
                json.encodeToString(SkillVersion(ts = 150, skillId = "farewell", project = false, content = "other")) + "\n"
        )
        val versionStore = VersionStore(dir.resolve("versions"))

        migrateSkillVersions(oldFile, versionStore)

        val greetHistory = versionStore.history(ArtifactType.SKILL, "greet")
        greetHistory.map { it.content } shouldBe listOf("v1", "v2")
        greetHistory.all { it.producedBy == ProducedBy.MIGRATION } shouldBe true
        versionStore.history(ArtifactType.SKILL, "farewell") shouldHaveSize 1
    }

    test("migrateSkillVersions is a no-op when the old file doesn't exist") {
        val dir = createTempDirectory("skill-migration-test")
        val versionStore = VersionStore(dir.resolve("versions"))

        migrateSkillVersions(dir.resolve("does-not-exist.jsonl"), versionStore)

        versionStore.history(ArtifactType.SKILL, "greet") shouldHaveSize 0
    }

    test("migrateSkillVersions skips a skillId that already has history (already migrated)") {
        val dir = createTempDirectory("skill-migration-test")
        val oldFile = dir.resolve(".versions.jsonl")
        oldFile.writeText(json.encodeToString(SkillVersion(ts = 100, skillId = "greet", project = false, content = "v1")) + "\n")
        val versionStore = VersionStore(dir.resolve("versions"))
        versionStore.record(ArtifactType.SKILL, "greet", "already here", ProducedBy.HUMAN)

        migrateSkillVersions(oldFile, versionStore)

        versionStore.history(ArtifactType.SKILL, "greet") shouldHaveSize 1
    }

    test("migrateSkillVersions is idempotent — calling it twice doesn't duplicate entries") {
        val dir = createTempDirectory("skill-migration-test")
        val oldFile = dir.resolve(".versions.jsonl")
        oldFile.writeText(json.encodeToString(SkillVersion(ts = 100, skillId = "greet", project = false, content = "v1")) + "\n")
        val versionStore = VersionStore(dir.resolve("versions"))

        migrateSkillVersions(oldFile, versionStore)
        migrateSkillVersions(oldFile, versionStore)

        versionStore.history(ArtifactType.SKILL, "greet") shouldHaveSize 1
    }
})
