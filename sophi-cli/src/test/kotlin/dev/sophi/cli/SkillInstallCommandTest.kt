package dev.sophi.cli

import dev.sophi.skills.SkillInstaller
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SkillInstallCommandTest : FunSpec({
    val installer = SkillInstaller()

    fun writeSkill(dir: Path, id: String) {
        val skillDir = dir.resolve(id).also { it.createDirectories() }
        skillDir.resolve("SKILL.md").writeText("---\nname: $id\ndescription: desc\n---\n\nBody.")
    }

    test("installs every skill found when only is not given") {
        val source = tempdir().toPath()
        writeSkill(source, "alpha")
        writeSkill(source, "beta")
        val target = tempdir().toPath()
        val output = mutableListOf<String>()

        SkillInstall(installer, target, source.toString(), only = null) { output.add(it) }.run()

        output shouldBe listOf("Installed: alpha", "Installed: beta")
    }

    test("only filters to just the requested skill ids") {
        val source = tempdir().toPath()
        writeSkill(source, "alpha")
        writeSkill(source, "beta")
        val target = tempdir().toPath()
        val output = mutableListOf<String>()

        SkillInstall(installer, target, source.toString(), only = "alpha") { output.add(it) }.run()

        output shouldBe listOf("Installed: alpha")
    }

    test("reports a requested-but-missing skill id from only") {
        val source = tempdir().toPath()
        writeSkill(source, "alpha")
        val target = tempdir().toPath()
        val output = mutableListOf<String>()

        SkillInstall(installer, target, source.toString(), only = "alpha,ghost") { output.add(it) }.run()

        output shouldBe listOf("Installed: alpha", "Not found: ghost")
    }

    test("reports when no skills are found under the source") {
        val source = tempdir().toPath()
        val target = tempdir().toPath()
        val output = mutableListOf<String>()

        SkillInstall(installer, target, source.toString(), only = null) { output.add(it) }.run()

        output shouldBe listOf("No skills found under $source.")
    }
})
