package dev.sophi.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

class BrowsingSitesSkillTest : FunSpec({
    test("docs/skills/browsing-sites.md parses cleanly via SkillLoader") {
        val repoRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { it.resolve("docs/skills/browsing-sites.md").toFile().exists() }
        val skill = SkillLoader().loadFile(repoRoot.resolve("docs/skills/browsing-sites.md"))

        skill.metadata.title shouldBe "Browsing sites"
        skill.metadata.tags shouldBe listOf("browsing", "site-learning")
        skill.body shouldContain "write_skill"
        skill.body shouldContain "site-<hostname>"
    }
})
