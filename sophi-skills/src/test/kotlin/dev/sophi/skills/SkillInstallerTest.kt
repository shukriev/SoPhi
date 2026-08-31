package dev.sophi.skills

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SkillInstallerTest : FunSpec({
    val installer = SkillInstaller()

    fun writeSkill(dir: Path, id: String, frontmatter: String, body: String, extraFile: Pair<String, String>? = null) {
        val skillDir = dir.resolve(id).also { it.createDirectories() }
        skillDir.resolve("SKILL.md").writeText("---\n$frontmatter\n---\n\n$body")
        extraFile?.let { (name, content) -> skillDir.resolve(name).writeText(content) }
    }

    test("installs a single skill from a local path source, normalizing frontmatter") {
        val source = createTempDirectory("source")
        val target = createTempDirectory("target")
        try {
            writeSkill(source, "code-review", "name: Code Review\ndescription: Reviews a diff", "Follow these steps.")
            val result = installer.install(source.toString(), target)
            result.installed shouldBe listOf("code-review")
            result.skipped shouldBe emptyList()
            result.notFound shouldBe emptyList()
            val content = target.resolve("code-review.md").readText()
            content shouldContain "title: Code Review"
            content shouldContain "description: Reviews a diff"
            content shouldContain "Follow these steps."
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("installs multiple skills sorted by id, filtering via only and reporting notFound") {
        val source = createTempDirectory("source")
        val target = createTempDirectory("target")
        try {
            writeSkill(source, "alpha", "name: Alpha\ndescription: A", "Body A.")
            writeSkill(source, "beta", "name: Beta\ndescription: B", "Body B.")
            writeSkill(source, "gamma", "name: Gamma\ndescription: G", "Body G.")
            val result = installer.install(source.toString(), target, only = setOf("alpha", "gamma", "ghost"))
            result.installed shouldBe listOf("alpha", "gamma")
            result.notFound shouldBe listOf("ghost")
            target.resolve("beta.md").exists() shouldBe false
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("skips a skill id that already exists in the target directory") {
        val source = createTempDirectory("source")
        val target = createTempDirectory("target")
        try {
            target.resolve("existing.md").writeText("---\ntitle: Existing\n---\n\nHand-edited, do not touch.")
            writeSkill(source, "existing", "name: Existing\ndescription: from source", "Fresh from source.")
            writeSkill(source, "new", "name: New\ndescription: n", "Fresh body.")
            val result = installer.install(source.toString(), target)
            result.installed shouldBe listOf("new")
            result.skipped shouldBe listOf("existing")
            target.resolve("existing.md").readText() shouldContain "Hand-edited, do not touch."
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("copies bundled sibling files and inserts a bundle-note line pointing at them") {
        val source = createTempDirectory("source")
        val target = createTempDirectory("target")
        try {
            writeSkill(
                source, "with-assets", "name: With Assets\ndescription: Has a helper script",
                "Run helper.sh when needed.", extraFile = "helper.sh" to "echo hi"
            )
            installer.install(source.toString(), target)
            val bundleDir = target.resolve("with-assets")
            bundleDir.resolve("helper.sh").readText() shouldBe "echo hi"
            target.resolve("with-assets.md").readText() shouldContain
                "Bundled files for this skill: ${bundleDir.toAbsolutePath()}"
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("does not create a bundle folder or note when a skill has no sibling files") {
        val source = createTempDirectory("source")
        val target = createTempDirectory("target")
        try {
            writeSkill(source, "plain", "name: Plain\ndescription: no assets", "Just prose.")
            installer.install(source.toString(), target)
            target.resolve("plain").exists() shouldBe false
            target.resolve("plain.md").readText() shouldContain "Just prose."
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("falls back to the folder name as title when name: is missing from frontmatter") {
        val source = createTempDirectory("source")
        val target = createTempDirectory("target")
        try {
            writeSkill(source, "no-name", "description: still has a description", "Body.")
            installer.install(source.toString(), target)
            target.resolve("no-name.md").readText() shouldContain "title: no-name"
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("still installs with title=id when frontmatter fails to parse") {
        val source = createTempDirectory("source")
        val target = createTempDirectory("target")
        try {
            writeSkill(source, "broken", "name: [unterminated", "Body survives even if frontmatter doesn't.")
            val result = installer.install(source.toString(), target)
            result.installed shouldBe listOf("broken")
            target.resolve("broken.md").readText() shouldContain "title: broken"
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("installs from a git URL source (local file:// clone), matching local-path behavior") {
        val repo = createTempDirectory("repo")
        val target = createTempDirectory("target")
        try {
            writeSkill(repo, "from-git", "name: From Git\ndescription: Cloned skill", "Cloned body.")
            listOf(
                listOf("git", "init", "-q", repo.toString()),
                listOf("git", "-C", repo.toString(), "config", "user.email", "test@example.com"),
                listOf("git", "-C", repo.toString(), "config", "user.name", "Test"),
                listOf("git", "-C", repo.toString(), "add", "."),
                listOf("git", "-C", repo.toString(), "commit", "-q", "-m", "init")
            ).forEach { ProcessBuilder(it).start().waitFor() }

            val result = installer.install("file://${repo.toAbsolutePath()}", target)
            result.installed shouldBe listOf("from-git")
            target.resolve("from-git.md").readText() shouldContain "Cloned body."
        } finally {
            repo.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("throws with a 'git clone failed' message when the clone fails") {
        val target = createTempDirectory("target")
        try {
            val ex = shouldThrow<IllegalStateException> {
                installer.install("https://example.invalid/does-not-exist.git", target)
            }
            ex.message shouldContain "git clone failed"
        } finally {
            target.toFile().deleteRecursively()
        }
    }

    test("remove deletes a single-file skill and returns true") {
        val target = createTempDirectory("target")
        try {
            target.resolve("solo.md").writeText("---\ntitle: Solo\n---\n\nBody.")
            val removed = installer.remove(target, "solo")
            removed shouldBe true
            target.resolve("solo.md").exists() shouldBe false
        } finally {
            target.toFile().deleteRecursively()
        }
    }

    test("remove also deletes the bundle directory for a multi-file skill") {
        val source = createTempDirectory("source")
        val target = createTempDirectory("target")
        try {
            writeSkill(
                source, "with-assets", "name: With Assets\ndescription: has a helper",
                "Run helper.sh.", extraFile = "helper.sh" to "echo hi"
            )
            installer.install(source.toString(), target)
            target.resolve("with-assets").isDirectory() shouldBe true

            val removed = installer.remove(target, "with-assets")

            removed shouldBe true
            target.resolve("with-assets.md").exists() shouldBe false
            target.resolve("with-assets").exists() shouldBe false
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("remove returns false, does not throw, for an id that doesn't exist") {
        val target = createTempDirectory("target")
        try {
            installer.remove(target, "ghost") shouldBe false
        } finally {
            target.toFile().deleteRecursively()
        }
    }

    test("install() rejects a skill whose content fails validate and does not write it") {
        val source = createTempDirectory("source")
        val target = createTempDirectory("target")
        try {
            writeSkill(source, "bad-skill", "name: Bad", "secret content")
            val result = installer.install(source.toString(), target) { _, content ->
                if (content.contains("secret")) listOf("contains the word secret") else emptyList()
            }
            result.rejected shouldBe mapOf("bad-skill" to listOf("contains the word secret"))
            result.installed shouldBe emptyList()
            target.resolve("bad-skill.md").exists() shouldBe false
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("install() with the default validate parameter behaves exactly as before") {
        val source = createTempDirectory("source")
        val target = createTempDirectory("target")
        try {
            writeSkill(source, "good-skill", "name: Good\ndescription: d", "body")
            val result = installer.install(source.toString(), target)
            result.installed shouldBe listOf("good-skill")
            result.rejected shouldBe emptyMap()
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    test("remove leaves other skills in the same directory untouched") {
        val target = createTempDirectory("target")
        try {
            target.resolve("keep.md").writeText("---\ntitle: Keep\n---\n\nStays.")
            target.resolve("gone.md").writeText("---\ntitle: Gone\n---\n\nGoes.")

            installer.remove(target, "gone")

            target.resolve("gone.md").exists() shouldBe false
            target.resolve("keep.md").readText() shouldContain "Stays."
        } finally {
            target.toFile().deleteRecursively()
        }
    }

    test("remove does not delete a same-named directory that has no bundle marker (not installer-owned)") {
        val target = createTempDirectory("target")
        try {
            target.resolve("site-maidplus-de.md").writeText("---\ntitle: MaidPlus\n---\n\nFlat skill, coincidentally same name.")
            val domainDir = target.resolve("site-maidplus-de").also { it.createDirectories() }
            domainDir.resolve("_index.md").writeText("---\ntitle: MaidPlus domain\n---\n\nA real domain, not a bundle.")

            val removed = installer.remove(target, "site-maidplus-de")

            removed shouldBe true
            target.resolve("site-maidplus-de.md").exists() shouldBe false
            domainDir.resolve("_index.md").exists() shouldBe true // NOT deleted — no bundle marker present
        } finally {
            target.toFile().deleteRecursively()
        }
    }
})
