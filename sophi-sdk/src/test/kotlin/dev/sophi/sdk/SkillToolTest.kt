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

    test("with a topK cap, the description lists at most that many skills") {
        val registry = SkillRegistry(mapOf(
            "a" to skill("A", "desc a", "body"),
            "b" to skill("B", "desc b", "body"),
            "c" to skill("C", "desc c", "body")
        ))
        val tool = SkillTool(registry, topK = 2)

        val listedCount = tool.description.lines().count { it.trimStart().startsWith("- ") }

        listedCount shouldBe 2
    }

    test("with no topK, every skill is listed (today's exact behavior, unchanged)") {
        val registry = SkillRegistry(mapOf(
            "a" to skill("A", "desc a", "body"),
            "b" to skill("B", "desc b", "body"),
            "c" to skill("C", "desc c", "body")
        ))
        val tool = SkillTool(registry)

        val listedCount = tool.description.lines().count { it.trimStart().startsWith("- ") }

        listedCount shouldBe 3
    }

    test("description reflects topLevel(), not all() — domain members excluded from the top-level list") {
        val registry = SkillRegistry(mapOf(
            "standalone" to skill("Standalone", "desc", "body"),
            "site-maidplus-de" to skill("MaidPlus", "MaidPlus platform", "orchestrator body"),
            "site-maidplus-de/companies" to skill("Companies", "Companies page", "body")
        ))
        val tool = SkillTool(registry)
        tool.description shouldContain "site-maidplus-de: MaidPlus platform"
        tool.description shouldContain "standalone: desc"
        (tool.description.contains("site-maidplus-de/companies")) shouldBe false
    }

    test("execute() on a domain-root id appends a generated child listing after the hand-authored body") {
        val registry = SkillRegistry(mapOf(
            "site-maidplus-de" to skill("MaidPlus", "MaidPlus platform", "Orchestrator body."),
            "site-maidplus-de/companies" to skill("Companies", "Companies page", "body"),
            "site-maidplus-de/dashboard" to skill("Dashboard", "Dashboard page", "body")
        ))
        val tool = SkillTool(registry)
        val result = runBlocking { tool.execute("""{"name":"site-maidplus-de"}""") }
        result shouldContain "Orchestrator body."
        result shouldContain "Available in this domain:"
        result shouldContain "site-maidplus-de/companies: Companies page"
        result shouldContain "site-maidplus-de/dashboard: Dashboard page"
    }
})
