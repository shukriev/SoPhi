package dev.sophi.sdk

import dev.sophi.core.tools.RiskLevel
import dev.sophi.skills.SkillMetadata
import dev.sophi.skills.SkillLoader
import dev.sophi.skills.SkillVersionStore
import dev.sophi.versioning.VersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText

private fun versionsIn(dir: Path, project: Boolean) = SkillVersionStore(VersionStore(dir.resolve(".versions")), project)

private const val VALID_ARGS = """
    {"id":"site-example-com","title":"example.com","description":"How to use example.com","tags":["site"],"body":"# example.com\n\nStep 1: navigate to /login."}
"""

class WriteSkillToolTest : FunSpec({
    lateinit var globalDir: Path
    lateinit var projectDir: Path
    lateinit var tool: WriteSkillTool

    beforeTest {
        globalDir = createTempDirectory("write-skill-global")
        projectDir = createTempDirectory("write-skill-project")
        tool = WriteSkillTool { project -> if (project) projectDir else globalDir }
    }

    test("execute() writes a skill file with real kaml frontmatter, not string templating") {
        val result = runBlocking { tool.execute(VALID_ARGS) }

        val written = globalDir.resolve("site-example-com.md")
        written.exists() shouldBe true
        val skill = SkillLoader().loadFile(written)
        skill.metadata shouldBe SkillMetadata(
            title = "example.com", description = "How to use example.com",
            tags = listOf("site")
        )
        skill.body shouldContain "Step 1: navigate to /login."
        result shouldContain "site-example-com"
    }

    test("execute() writes to the project dir when project=true") {
        val args = """{"id":"site-example-com","title":"t","description":"d","tags":[],"body":"b","project":true}"""
        runBlocking { tool.execute(args) }

        projectDir.resolve("site-example-com.md").exists() shouldBe true
        globalDir.resolve("site-example-com.md").exists() shouldBe false
    }

    test("execute() overwrites an existing matching site-* id (idempotent updates)") {
        runBlocking { tool.execute(VALID_ARGS) }
        val updated = VALID_ARGS.replace("How to use example.com", "Updated notes")

        runBlocking { tool.execute(updated) }

        SkillLoader().loadFile(globalDir.resolve("site-example-com.md")).metadata.description shouldBe "Updated notes"
    }

    test("execute() rejects an id outside the site-<slug> pattern") {
        val args = """{"id":"browsing-sites","title":"t","description":"d","tags":[],"body":"b"}"""

        val result = runBlocking { tool.execute(args) }

        result shouldContain "Error"
        globalDir.resolve("browsing-sites.md").exists() shouldBe false
    }

    test("execute() rejects an id with path-traversal characters") {
        val args = """{"id":"site-../../etc-passwd","title":"t","description":"d","tags":[],"body":"b"}"""

        val result = runBlocking { tool.execute(args) }

        result shouldContain "Error"
    }

    test("name is write_skill") {
        tool.name shouldBe "write_skill"
    }

    test("riskLevel is always DESTRUCTIVE") {
        tool.riskLevel("{}") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("execute() records a version on a successful write") {
        runBlocking { tool.execute(VALID_ARGS) }

        val versions = versionsIn(globalDir, project = false).history("site-example-com", project = false)

        versions shouldHaveSize 1
        versions.first().content shouldContain "Step 1: navigate to /login."
    }

    test("execute() records a new version per overwrite, not just the first write") {
        runBlocking { tool.execute(VALID_ARGS) }
        val updated = VALID_ARGS.replace("How to use example.com", "Updated notes")
        runBlocking { tool.execute(updated) }

        val versions = versionsIn(globalDir, project = false).history("site-example-com", project = false)

        versions shouldHaveSize 2
        versions.first().content shouldContain "Updated notes"
    }

    test("execute() with project=true records into the project store, not the global one") {
        val args = """{"id":"site-example-com","title":"t","description":"d","tags":[],"body":"b","project":true}"""
        runBlocking { tool.execute(args) }

        versionsIn(projectDir, project = true).history("site-example-com", project = true) shouldHaveSize 1
        versionsIn(globalDir, project = false).history("site-example-com", project = false) shouldHaveSize 0
    }

    test("a rejected write (bad id pattern) records no version") {
        val badArgs = """{"id":"not-a-site-id","title":"t","description":"d","tags":[],"body":"b"}"""
        runBlocking { tool.execute(badArgs) }

        versionsIn(globalDir, project = false).history("not-a-site-id", project = false) shouldHaveSize 0
    }

    test("execute() records the version as trial = true, not the SkillVersion default of false") {
        runBlocking { tool.execute(VALID_ARGS) }

        val versions = versionsIn(globalDir, project = false).history("site-example-com", project = false)

        versions.single().trial shouldBe true
    }

    test("a file with no existing version history gets a root baseline snapshot before the new write is recorded") {
        val existing = globalDir.resolve("site-example-com.md")
        existing.writeText("---\ntitle: Legacy\ndescription: pre-existing\n---\nPre-existing content nobody versioned.")

        runBlocking { tool.execute(VALID_ARGS) }

        // history() is newest-first: last() is the baseline snapshot, first() is the new write.
        val history = versionsIn(globalDir, project = false).history("site-example-com", project = false)
        history shouldHaveSize 2
        history.last().content shouldContain "Pre-existing content nobody versioned"
        history.last().trial shouldBe false
        history.first().content shouldContain "Step 1: navigate to /login."
        history.first().trial shouldBe true
    }

    test("a file already under version control does not get a duplicate baseline snapshot") {
        runBlocking { tool.execute(VALID_ARGS) }

        runBlocking { tool.execute(VALID_ARGS.replace("How to use example.com", "Second write")) }

        versionsIn(globalDir, project = false).history("site-example-com", project = false) shouldHaveSize 2 // not 3
    }
})
