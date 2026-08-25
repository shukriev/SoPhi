package dev.sophi.sdk

import dev.sophi.core.tools.RiskLevel
import dev.sophi.skills.Skill
import dev.sophi.skills.SkillMetadata
import dev.sophi.skills.SkillRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class SkillToolTest : FunSpec({

    fun skill(title: String, description: String, body: String) =
        Skill(SkillMetadata(title = title, description = description), body, Path.of("$title.md"))

    test("description lists every loaded skill's id and description") {
        val registry = SkillRegistry(mapOf(
            "code-review" to skill("Code Review", "Reviews a diff for issues", "body1"),
            "release-notes" to skill("Release Notes", "Drafts release notes", "body2")
        ))
        val tool = SkillTool(registry)
        tool.description shouldContain "code-review: Reviews a diff for issues"
        tool.description shouldContain "release-notes: Drafts release notes"
    }

    test("execute returns the skill body for a known id") {
        val registry = SkillRegistry(mapOf("code-review" to skill("Code Review", "desc", "Follow these steps.")))
        val tool = SkillTool(registry)
        val result = runBlocking { tool.execute("""{"name":"code-review"}""") }
        result shouldBe "Follow these steps."
    }

    test("execute returns a not-found error for an unknown id") {
        val tool = SkillTool(SkillRegistry(emptyMap()))
        val result = runBlocking { tool.execute("""{"name":"nonexistent"}""") }
        result shouldBe "Error: skill not found: nonexistent"
    }

    test("execute returns a missing-argument error when name is absent") {
        val tool = SkillTool(SkillRegistry(emptyMap()))
        val result = runBlocking { tool.execute("""{}""") }
        result shouldBe "Error: missing 'name' argument"
    }

    test("riskLevel is always SAFE") {
        val tool = SkillTool(SkillRegistry(emptyMap()))
        tool.riskLevel("""{"name":"anything"}""") shouldBe RiskLevel.SAFE
    }
})
