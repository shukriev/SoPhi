package dev.sophi.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class SkillRegistryTest : FunSpec({

    test("load() merges global and project directories") {
        val global = createTempDirectory("skills-global")
        val project = createTempDirectory("skills-project")
        try {
            global.resolve("alpha.md").writeText("---\ntitle: Alpha\ndescription: from global\n---\n\nGlobal body.")
            project.resolve("beta.md").writeText("---\ntitle: Beta\ndescription: from project\n---\n\nProject body.")
            val registry = SkillRegistry.load(global, project)
            registry.all() shouldHaveSize 2
            registry.get("alpha")?.metadata?.title shouldBe "Alpha"
            registry.get("beta")?.metadata?.title shouldBe "Beta"
        } finally {
            global.toFile().deleteRecursively()
            project.toFile().deleteRecursively()
        }
    }

    test("load() lets a project-local skill override a global skill with the same id") {
        val global = createTempDirectory("skills-global")
        val project = createTempDirectory("skills-project")
        try {
            global.resolve("code-review.md").writeText("---\ntitle: Global Review\n---\n\nGlobal instructions.")
            project.resolve("code-review.md").writeText("---\ntitle: Project Review\n---\n\nProject instructions.")
            val registry = SkillRegistry.load(global, project)
            registry.all() shouldHaveSize 1
            registry.get("code-review")?.metadata?.title shouldBe "Project Review"
        } finally {
            global.toFile().deleteRecursively()
            project.toFile().deleteRecursively()
        }
    }

    test("load() tolerates a missing global directory") {
        val project = createTempDirectory("skills-project")
        try {
            project.resolve("beta.md").writeText("---\ntitle: Beta\n---\n\nBody.")
            val registry = SkillRegistry.load(project.resolve("does-not-exist"), project)
            registry.all() shouldHaveSize 1
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    test("load() tolerates both directories missing, returning an empty registry") {
        val base = createTempDirectory("skills-base")
        try {
            val registry = SkillRegistry.load(base.resolve("global"), base.resolve("project"))
            registry.all() shouldBe emptyList()
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    test("load() skips a malformed skill file but still loads its siblings") {
        val dir = createTempDirectory("skills-mixed")
        try {
            dir.resolve("good.md").writeText("---\ntitle: Good\n---\n\nFine.")
            dir.resolve("bad.md").writeText("---\ntitle: [unterminated\n---\n\nBroken.")
            val registry = SkillRegistry.load(dir.resolve("no-global"), dir)
            registry.all() shouldHaveSize 1
            registry.get("good")?.metadata?.title shouldBe "Good"
            registry.get("bad") shouldBe null
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("get() returns null for an unknown id") {
        val registry = SkillRegistry(emptyMap())
        registry.get("nonexistent") shouldBe null
    }

    fun skillFixture(title: String) = Skill(SkillMetadata(title = title), "body", Path.of("$title.md"))

    test("load() treats a subdirectory with _index.md as a domain, its siblings as members") {
        val dir = createTempDirectory("skills-domain")
        try {
            val domain = dir.resolve("site-maidplus-de").also { it.createDirectories() }
            domain.resolve("_index.md").writeText("---\ntitle: MaidPlus\ndescription: MaidPlus platform\n---\n\nOrchestrator body.")
            domain.resolve("companies.md").writeText("---\ntitle: Companies\ndescription: Companies page\n---\n\nCompanies body.")
            val registry = SkillRegistry.load(dir.resolve("no-global"), dir)
            registry.get("site-maidplus-de")?.metadata?.title shouldBe "MaidPlus"
            registry.get("site-maidplus-de/companies")?.metadata?.title shouldBe "Companies"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load() loads domain members into all() even before _index.md exists, but keeps the domain out of topLevel()") {
        val dir = createTempDirectory("skills-domain-orphan")
        try {
            val domain = dir.resolve("site-maidplus-de").also { it.createDirectories() }
            domain.resolve("companies.md").writeText("---\ntitle: Companies\n---\n\nBody.")
            val registry = SkillRegistry.load(dir.resolve("no-global"), dir)
            registry.get("site-maidplus-de/companies")?.metadata?.title shouldBe "Companies"
            registry.topLevel() shouldBe emptyList()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load() excludes a subdirectory that has a same-named flat sibling (installed bundle)") {
        val dir = createTempDirectory("skills-bundle")
        try {
            dir.resolve("skill-creator.md").writeText("---\ntitle: Skill Creator\n---\n\nBody.")
            val bundleDir = dir.resolve("skill-creator").also { it.createDirectories() }
            bundleDir.resolve("README.md").writeText("---\ntitle: readme\n---\n\nnotes")
            val registry = SkillRegistry.load(dir.resolve("no-global"), dir)
            registry.all() shouldHaveSize 1
            registry.get("skill-creator")?.metadata?.title shouldBe "Skill Creator"
            registry.get("skill-creator/README") shouldBe null
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("topLevel() excludes domain members but includes domain roots and standalone skills") {
        val registry = SkillRegistry(mapOf(
            "standalone" to skillFixture("Standalone"),
            "site-maidplus-de" to skillFixture("MaidPlus"),
            "site-maidplus-de/companies" to skillFixture("Companies")
        ))
        registry.topLevel().map { it.first } shouldBe listOf("site-maidplus-de", "standalone")
    }

    test("childrenOf() returns a domain's members sorted, empty for a non-domain id") {
        val registry = SkillRegistry(mapOf(
            "site-maidplus-de" to skillFixture("MaidPlus"),
            "site-maidplus-de/dashboard" to skillFixture("Dashboard"),
            "site-maidplus-de/companies" to skillFixture("Companies")
        ))
        registry.childrenOf("site-maidplus-de").map { it.first } shouldBe
            listOf("site-maidplus-de/companies", "site-maidplus-de/dashboard")
        registry.childrenOf("standalone") shouldBe emptyList()
    }

    test("load() reads other agents' <name>/SKILL.md skills (Claude Code, .agents, agentskills.io)") {
        val sophi = createTempDirectory("skills-sophi")
        val claude = createTempDirectory("skills-claude")
        try {
            claude.resolve("pdf-tools/scripts").createDirectories()
            claude.resolve("pdf-tools/SKILL.md").writeText(
                "---\nname: pdf-tools\ndescription: Fill PDF forms\nallowed-tools: Bash\n---\n\nRun scripts/fill.py."
            )
            claude.resolve("pdf-tools/scripts/fill.py").writeText("print('hi')")
            val registry = SkillRegistry.load(sophi, sophi, foreignDirs = listOf(claude))
            registry.all().map { it.first } shouldBe listOf("pdf-tools")
            val skill = registry.get("pdf-tools")!!
            skill.metadata.title shouldBe "pdf-tools"
            skill.metadata.description shouldBe "Fill PDF forms"
            skill.body shouldContain "Run scripts/fill.py."
            skill.body shouldContain claude.resolve("pdf-tools").toAbsolutePath().toString()
        } finally {
            sophi.toFile().deleteRecursively()
            claude.toFile().deleteRecursively()
        }
    }

    test("load() lets SoPhi's own skill win over another agent's skill with the same name") {
        val sophi = createTempDirectory("skills-sophi")
        val claude = createTempDirectory("skills-claude")
        try {
            sophi.resolve("review.md").writeText("---\ntitle: SoPhi Review\n---\n\nOurs.")
            claude.resolve("review").createDirectories()
            claude.resolve("review/SKILL.md").writeText("---\nname: review\ndescription: theirs\n---\n\nTheirs.")
            SkillRegistry.load(sophi, sophi, foreignDirs = listOf(claude)).get("review")?.metadata?.title shouldBe "SoPhi Review"
        } finally {
            sophi.toFile().deleteRecursively()
            claude.toFile().deleteRecursively()
        }
    }

    test("load() skips a foreign skill with malformed frontmatter, says so, and loads the rest") {
        val sophi = createTempDirectory("skills-sophi")
        val claude = createTempDirectory("skills-claude")
        try {
            claude.resolve("broken").createDirectories()
            claude.resolve("broken/SKILL.md").writeText("---\nname: [unclosed\n---\n\nBody.")
            claude.resolve("fine").createDirectories()
            claude.resolve("fine/SKILL.md").writeText("---\nname: fine\ndescription: ok\n---\n\nBody.")
            val warnings = mutableListOf<String>()
            val registry = SkillRegistry.load(sophi, sophi, foreignDirs = listOf(claude), onWarning = { warnings += it })
            registry.all().map { it.first } shouldBe listOf("fine")
            warnings shouldHaveSize 1
            warnings.single() shouldContain "broken"
        } finally {
            sophi.toFile().deleteRecursively()
            claude.toFile().deleteRecursively()
        }
    }

    test("foreignDirs() lists user-wide dirs before project ones, so the project's win") {
        val home = Path.of("/h")
        val project = Path.of("/p")
        SkillRegistry.foreignDirs(home, project) shouldBe listOf(
            home.resolve(".claude/skills"), home.resolve(".agents/skills"),
            project.resolve(".claude/skills"), project.resolve(".agents/skills")
        )
    }
})
