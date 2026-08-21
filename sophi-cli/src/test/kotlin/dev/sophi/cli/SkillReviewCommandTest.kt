package dev.sophi.cli

import dev.sophi.learning.ToolEvent
import dev.sophi.sdk.SkillVersionAttribution
import dev.sophi.skills.SkillInvocationEvent
import dev.sophi.skills.SkillInvocationStore
import dev.sophi.skills.SkillVersion
import dev.sophi.skills.SkillVersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines

private fun writeToolEvent(learningHome: Path, event: ToolEvent) {
    learningHome.createDirectories()
    val line = Json.encodeToString(ToolEvent.serializer(), event)
    Files.write(learningHome.resolve("tool-events.jsonl"), (line + "\n").toByteArray(), CREATE, APPEND)
}

class SkillReviewCommandTest : FunSpec({
    test("review prints invocation and adjacent-failure counts for a skill with a version") {
        val skillsHome = createTempDirectory("skill-review-skills").also { it.createDirectories() }
        val projectSkillsHome = createTempDirectory("skill-review-project-empty")
        val learningHome = createTempDirectory("skill-review-learning")
        SkillVersionStore(skillsHome.resolve(".versions.jsonl"))
            .record(SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "c"))
        SkillInvocationStore(skillsHome.resolve(".invocations.jsonl"))
            .record(SkillInvocationEvent(ts = 200L, sessionId = "s1", skillId = "site-a"))
        writeToolEvent(learningHome, ToolEvent(ts = 300L, scope = "/p", sessionId = "s1", tool = "write_file", success = false, durationMillis = 1))

        val lines = mutableListOf<String>()
        SkillReview(skillsHome, projectSkillsHome, learningHome, filterId = null) { lines.add(it) }.run()

        lines.any { it.contains("invocations=1") && it.contains("adjacentFailures=1") } shouldBe true
    }

    test("review persists both snapshot files, fully overwritten not appended, across two runs") {
        val skillsHome = createTempDirectory("skill-review-snapshot").also { it.createDirectories() }
        val projectSkillsHome = createTempDirectory("skill-review-snapshot-project-empty")
        val learningHome = createTempDirectory("skill-review-snapshot-learning")
        SkillVersionStore(skillsHome.resolve(".versions.jsonl"))
            .record(SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "c"))
        SkillInvocationStore(skillsHome.resolve(".invocations.jsonl"))
            .record(SkillInvocationEvent(ts = 50L, sessionId = "s1", skillId = "site-unversioned"))

        SkillReview(skillsHome, projectSkillsHome, learningHome, filterId = null) {}.run()
        SkillReview(skillsHome, projectSkillsHome, learningHome, filterId = null) {}.run()

        skillsHome.resolve(".attribution.jsonl").readLines() shouldHaveSize 1
        skillsHome.resolve(".unattributed.jsonl").readLines() shouldHaveSize 1
    }

    test("a project-scoped version is attributed even when a same-id global version also exists") {
        val skillsHome = createTempDirectory("skill-review-project").also { it.createDirectories() }
        val projectSkillsHome = createTempDirectory("skill-review-project-scoped").also { it.createDirectories() }
        val learningHome = createTempDirectory("skill-review-project-learning")
        SkillVersionStore(skillsHome.resolve(".versions.jsonl"))
            .record(SkillVersion(id = "global-v1", ts = 100L, skillId = "site-a", project = false, content = "global"))
        SkillVersionStore(projectSkillsHome.resolve(".versions.jsonl"))
            .record(SkillVersion(id = "project-v1", ts = 200L, skillId = "site-a", project = true, content = "project"))
        SkillInvocationStore(skillsHome.resolve(".invocations.jsonl"))
            .record(SkillInvocationEvent(ts = 250L, sessionId = "s1", skillId = "site-a"))

        val lines = mutableListOf<String>()
        SkillReview(skillsHome, projectSkillsHome, learningHome, filterId = null) { lines.add(it) }.run()

        lines.any { it.contains("project-v1") && it.contains("invocations=1") } shouldBe true
        lines.none { it.contains("global-v1") } shouldBe true
    }

    test("passing a filterId narrows printed output without changing either persisted snapshot") {
        val skillsHome = createTempDirectory("skill-review-filter").also { it.createDirectories() }
        val projectSkillsHome = createTempDirectory("skill-review-filter-project-empty")
        val learningHome = createTempDirectory("skill-review-filter-learning")
        SkillVersionStore(skillsHome.resolve(".versions.jsonl")).record(SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "c"))
        SkillVersionStore(skillsHome.resolve(".versions.jsonl")).record(SkillVersion(id = "v2", ts = 200L, skillId = "site-b", project = false, content = "c"))

        val lines = mutableListOf<String>()
        SkillReview(skillsHome, projectSkillsHome, learningHome, filterId = "site-a") { lines.add(it) }.run()

        lines.any { it.contains("site-b") } shouldBe false
        skillsHome.resolve(".attribution.jsonl").readLines() shouldHaveSize 2
    }

    test("the persisted attribution snapshot round-trips back into SkillVersionAttribution") {
        val skillsHome = createTempDirectory("skill-review-roundtrip").also { it.createDirectories() }
        val projectSkillsHome = createTempDirectory("skill-review-roundtrip-project-empty")
        val learningHome = createTempDirectory("skill-review-roundtrip-learning")
        SkillVersionStore(skillsHome.resolve(".versions.jsonl"))
            .record(SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "c", trial = true))

        SkillReview(skillsHome, projectSkillsHome, learningHome, filterId = null) {}.run()

        val json = Json { ignoreUnknownKeys = true }
        val decoded = skillsHome.resolve(".attribution.jsonl").readLines()
            .map { json.decodeFromString<SkillVersionAttribution>(it) }
        decoded.single().skillId shouldBe "site-a"
        decoded.single().trial shouldBe true
    }
})
