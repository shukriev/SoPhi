package dev.sophi.sdk

import dev.sophi.core.tools.RiskLevel
import dev.sophi.skills.SkillMetadata
import dev.sophi.skills.SkillLoader
import dev.sophi.skills.SkillVersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

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

        val versions = SkillVersionStore(globalDir.resolve(".versions.jsonl")).history("site-example-com", project = false)

        versions shouldHaveSize 1
        versions.first().content shouldContain "Step 1: navigate to /login."
    }

    test("execute() records a new version per overwrite, not just the first write") {
        runBlocking { tool.execute(VALID_ARGS) }
        val updated = VALID_ARGS.replace("How to use example.com", "Updated notes")
        runBlocking { tool.execute(updated) }

        val versions = SkillVersionStore(globalDir.resolve(".versions.jsonl")).history("site-example-com", project = false)

        versions shouldHaveSize 2
        versions.first().content shouldContain "Updated notes"
    }

    test("execute() with project=true records into the project versions log, not the global one") {
        val args = """{"id":"site-example-com","title":"t","description":"d","tags":[],"body":"b","project":true}"""
        runBlocking { tool.execute(args) }

        SkillVersionStore(projectDir.resolve(".versions.jsonl")).history("site-example-com", project = true) shouldHaveSize 1
        SkillVersionStore(globalDir.resolve(".versions.jsonl")).history("site-example-com", project = false) shouldHaveSize 0
    }

    test("a rejected write (bad id pattern) records no version") {
        val badArgs = """{"id":"not-a-site-id","title":"t","description":"d","tags":[],"body":"b"}"""
        runBlocking { tool.execute(badArgs) }

        SkillVersionStore(globalDir.resolve(".versions.jsonl")).history("not-a-site-id", project = false) shouldHaveSize 0
    }

    test("execute() records the version as trial = true, not the SkillVersion default of false") {
        runBlocking { tool.execute(VALID_ARGS) }

        val versions = SkillVersionStore(globalDir.resolve(".versions.jsonl")).history("site-example-com", project = false)

        versions.single().trial shouldBe true
    }
})
