package dev.sophi.sdk

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.RuleVerdict
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class InstallSkillToolTest : FunSpec({

    fun writeSkill(dir: Path, id: String) {
        val skillDir = dir.resolve(id).also { it.createDirectories() }
        skillDir.resolve("SKILL.md").writeText("---\nname: $id\ndescription: desc\n---\n\nBody.")
    }

    test("riskLevel is always DESTRUCTIVE") {
        val tool = InstallSkillTool()
        tool.riskLevel("""{"source":"anything"}""") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("ruleVerdict is always HIGH_RISK") {
        val tool = InstallSkillTool()
        tool.ruleVerdict("""{"source":"anything"}""") shouldBe RuleVerdict.HIGH_RISK
    }

    test("execute installs skills into the resolved target directory and reports them") {
        val source = tempdir().toPath()
        writeSkill(source, "code-review")
        val target = tempdir().toPath()
        val tool = InstallSkillTool(resolveTargetDir = { target })

        val result = runBlocking { tool.execute("""{"source":"$source"}""") }

        result shouldBe "Installed: code-review"
        target.resolve("code-review.md").exists() shouldBe true
    }

    test("execute reports skipped and not-found skills") {
        val source = tempdir().toPath()
        writeSkill(source, "alpha")
        val target = tempdir().toPath()
        target.resolve("alpha.md").writeText("---\ntitle: Alpha\n---\n\nAlready here.")
        val tool = InstallSkillTool(resolveTargetDir = { target })

        val result = runBlocking { tool.execute("""{"source":"$source","only":["alpha","ghost"]}""") }

        result shouldBe "Already installed: alpha\nNot found: ghost"
    }

    test("execute returns an Error string for invalid JSON arguments") {
        val tool = InstallSkillTool()
        val result = runBlocking { tool.execute("not json") }
        result shouldBe "Error: invalid arguments"
    }
})
