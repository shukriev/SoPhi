package dev.sophi.cli

import dev.sophi.skills.SkillLoader
import dev.sophi.skills.SkillVersion
import dev.sophi.skills.SkillVersionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

private const val SKILL_ID = "site-example-com"

class SkillVersionCommandsTest : FunSpec({
    fun globalDir(): Path = createTempDirectory("skill-version-commands").also { it.createDirectories() }

    fun writeSkillFile(dir: Path, body: String) {
        dir.resolve("$SKILL_ID.md").writeText("---\ntitle: t\ndescription: d\n---\n$body")
    }

    test("versions reports 'no versions' for a skill with no recorded history") {
        val lines = mutableListOf<String>()
        SkillVersionsList(globalDir(), SKILL_ID, project = false) { lines.add(it) }.run()

        lines shouldHaveSize 1
        lines.first() shouldContain "No versions"
    }

    test("versions lists recorded versions newest first") {
        val home = globalDir()
        val store = SkillVersionStore(home.resolve(".versions.jsonl"))
        val v1 = store.record(SkillVersion(skillId = SKILL_ID, project = false, content = "one"))
        val v2 = store.record(SkillVersion(skillId = SKILL_ID, project = false, content = "two"))

        val lines = mutableListOf<String>()
        SkillVersionsList(home, SKILL_ID, project = false) { lines.add(it) }.run()

        lines shouldHaveSize 2
        lines[0] shouldContain v2.id
        lines[1] shouldContain v1.id
    }

    test("revert writes the stored content back to the skill file and records a new version") {
        val home = globalDir()
        writeSkillFile(home, "current body")
        val store = SkillVersionStore(home.resolve(".versions.jsonl"))
        val original = store.record(
            SkillVersion(skillId = SKILL_ID, project = false, content = "---\ntitle: t\ndescription: d\n---\noriginal body")
        )

        val lines = mutableListOf<String>()
        SkillVersionsRevert(home, SKILL_ID, original.id, project = false) { lines.add(it) }.run()

        SkillLoader().loadFile(home.resolve("$SKILL_ID.md")).body shouldContain "original body"
        val history = store.history(SKILL_ID, project = false)
        history shouldHaveSize 2
        history.first().content shouldContain "original body"
        lines.first() shouldContain "Reverted"
    }

    test("revert reports failure for an unknown version id") {
        val lines = mutableListOf<String>()
        SkillVersionsRevert(globalDir(), SKILL_ID, "skillver_does-not-exist", project = false) { lines.add(it) }.run()

        lines.first() shouldContain "No version found"
    }
})
