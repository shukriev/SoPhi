package dev.sophi.skills

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class SkillLoaderTest : FunSpec({
    val loader = SkillLoader()

    test("load() returns one Skill per .md file in the directory") {
        val dir = createTempDirectory("skills-test")
        try {
            dir.resolve("a.md").writeText("---\ntitle: Alpha\n---\n\nBody A.")
            dir.resolve("b.md").writeText("---\ntitle: Beta\n---\n\nBody B.")
            val skills = loader.load(dir)
            skills shouldHaveSize 2
            skills.map { it.metadata.title } shouldContainExactlyInAnyOrder listOf("Alpha", "Beta")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load() ignores non-.md files") {
        val dir = createTempDirectory("skills-test")
        try {
            dir.resolve("skill.md").writeText("---\ntitle: Real\n---\n\nBody.")
            dir.resolve("readme.txt").writeText("should be ignored")
            loader.load(dir) shouldHaveSize 1
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load() returns empty list for empty directory") {
        val dir = createTempDirectory("skills-empty")
        try {
            loader.load(dir) shouldBe emptyList()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load() throws IllegalArgumentException when path is not a directory") {
        val file = createTempFile("not-a-dir", ".md")
        try {
            shouldThrow<IllegalArgumentException> { loader.load(file) }
        } finally {
            file.deleteIfExists()
        }
    }

    test("loadFile() populates source path with the file's path") {
        val dir = createTempDirectory("skills-test")
        try {
            val file = dir.resolve("solo.md")
            file.writeText("---\ntitle: Solo\n---\nContent.")
            val skill = loader.loadFile(file)
            skill.source shouldBe file
            skill.metadata.title shouldBe "Solo"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
