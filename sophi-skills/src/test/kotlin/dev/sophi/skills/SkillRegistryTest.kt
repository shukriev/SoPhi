package dev.sophi.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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

    test("load() with no projectDir loads only the global directory") {
        val global = createTempDirectory("skills-global")
        try {
            global.resolve("alpha.md").writeText("---\ntitle: Alpha\n---\n\nGlobal body.")
            val registry = SkillRegistry.load(global)
            registry.all() shouldHaveSize 1
            registry.get("alpha")?.metadata?.title shouldBe "Alpha"
        } finally {
            global.toFile().deleteRecursively()
        }
    }
})
