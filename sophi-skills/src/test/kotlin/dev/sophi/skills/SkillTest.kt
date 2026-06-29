package dev.sophi.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SkillTest : FunSpec({

    test("parseFrontmatter() extracts metadata and body from standard frontmatter") {
        val md = "---\ntitle: My Skill\ndescription: Does something\n---\n\n# Body here\n"
        val (meta, body) = parseFrontmatter(md)
        meta.title shouldBe "My Skill"
        meta.description shouldBe "Does something"
        body.trim() shouldBe "# Body here"
    }

    test("parseFrontmatter() returns Untitled and full content when no frontmatter delimiter") {
        val md = "# Just a body\n"
        val (meta, body) = parseFrontmatter(md)
        meta.title shouldBe "Untitled"
        body shouldBe md
    }

    test("parseFrontmatter() populates tags list from YAML sequence") {
        val md = "---\ntitle: Tagged\ntags:\n  - search\n  - reasoning\n---\n\nBody.\n"
        val (meta, _) = parseFrontmatter(md)
        meta.tags shouldBe listOf("search", "reasoning")
    }

    test("parseFrontmatter() uses defaults for missing optional fields") {
        val md = "---\ntitle: Minimal\n---\nBody."
        val (meta, _) = parseFrontmatter(md)
        meta.description shouldBe ""
        meta.version shouldBe "1.0.0"
        meta.tags shouldBe emptyList()
    }

    test("parseFrontmatter() returns Untitled when there is no closing --- delimiter") {
        val md = "---\ntitle: Bad\n\nNo closing delimiter."
        val (meta, body) = parseFrontmatter(md)
        meta.title shouldBe "Untitled"
        body shouldBe md
    }
})
