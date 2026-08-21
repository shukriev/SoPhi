package dev.sophi.core.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class AgentDefinitionLoaderTest : FunSpec({
    val loader = AgentDefinitionLoader()

    test("load() returns one AgentDefinition per .md file in the directory") {
        val dir = createTempDirectory("agent-defs-test")
        try {
            dir.resolve("a.md").writeText("---\nname: alpha\ndescription: A.\n---\nBody A.")
            dir.resolve("b.md").writeText("---\nname: beta\ndescription: B.\n---\nBody B.")
            val defs = loader.load(dir)
            defs shouldHaveSize 2
            defs.map { it.name } shouldContainExactlyInAnyOrder listOf("alpha", "beta")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load() ignores non-.md files") {
        val dir = createTempDirectory("agent-defs-test")
        try {
            dir.resolve("real.md").writeText("---\nname: real\ndescription: R.\n---\nBody.")
            dir.resolve("readme.txt").writeText("should be ignored")
            loader.load(dir) shouldHaveSize 1
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load() returns empty list for empty directory") {
        val dir = createTempDirectory("agent-defs-empty")
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

    test("loadFile() populates fields from a single file") {
        val dir = createTempDirectory("agent-defs-test")
        try {
            val file = dir.resolve("solo.md")
            file.writeText("---\nname: solo\ndescription: Solo agent.\n---\nSolo prompt.")
            val definition = loader.loadFile(file)
            definition.name shouldBe "solo"
            definition.systemPrompt shouldBe "Solo prompt."
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("loadOrWarn() returns the loaded definitions when the directory parses cleanly") {
        val dir = createTempDirectory("agent-defs-loadorwarn-test")
        try {
            dir.resolve("a.md").writeText("---\nname: alpha\ndescription: A.\n---\nBody A.")
            val warnings = mutableListOf<String>()
            val defs = loader.loadOrWarn(dir, onWarning = { warnings.add(it) })
            defs.map { it.name } shouldBe listOf("alpha")
            warnings shouldBe emptyList()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("loadOrWarn() returns empty list and invokes onWarning when a file is malformed") {
        val dir = createTempDirectory("agent-defs-loadorwarn-test")
        try {
            dir.resolve("broken.md").writeText("not a valid agent definition")
            val warnings = mutableListOf<String>()
            val defs = loader.loadOrWarn(dir, onWarning = { warnings.add(it) })
            defs shouldBe emptyList()
            warnings shouldHaveSize 1
            warnings.single() shouldContain dir.toString()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("loadOrWarn() creates a missing directory and returns empty list without warning") {
        val parent = createTempDirectory("agent-defs-loadorwarn-missing-test")
        val dir = parent.resolve("agents")
        try {
            val warnings = mutableListOf<String>()
            val defs = loader.loadOrWarn(dir, onWarning = { warnings.add(it) })
            defs shouldBe emptyList()
            warnings shouldBe emptyList()
            dir.toFile().exists() shouldBe true
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
})
