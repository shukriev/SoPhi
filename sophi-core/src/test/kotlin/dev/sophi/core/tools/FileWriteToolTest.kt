package dev.sophi.core.tools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileWriteToolTest : FunSpec({
    lateinit var root: Path
    lateinit var tool: FileWriteTool

    beforeTest {
        root = createTempDirectory("sophi-file-write-test")
        tool = FileWriteTool(root)
    }

    test("execute() writes a new file with the given content") {
        val result = runBlocking { tool.execute("""{"path":"hello.txt","content":"hello world"}""") }
        root.resolve("hello.txt").readText() shouldBe "hello world"
        result shouldBe "Wrote 11 bytes to hello.txt"
    }

    test("execute() overwrites an existing file's content") {
        root.resolve("hello.txt").writeText("old content")
        runBlocking { tool.execute("""{"path":"hello.txt","content":"new content"}""") }
        root.resolve("hello.txt").readText() shouldBe "new content"
    }

    test("execute() creates missing parent directories") {
        runBlocking { tool.execute("""{"path":"notes/2026/file.md","content":"hi"}""") }
        root.resolve("notes/2026/file.md").exists() shouldBe true
        root.resolve("notes/2026/file.md").readText() shouldBe "hi"
    }

    test("execute() throws IllegalArgumentException when path escapes root") {
        shouldThrow<IllegalArgumentException> {
            runBlocking { tool.execute("""{"path":"../outside.txt","content":"x"}""") }
        }
    }

    test("execute() throws IllegalArgumentException when path is an existing directory") {
        root.resolve("subdir").createDirectory()
        shouldThrow<IllegalArgumentException> {
            runBlocking { tool.execute("""{"path":"subdir","content":"x"}""") }
        }
    }

    test("execute() throws IllegalArgumentException when content exceeds the size cap") {
        val big = "x".repeat(1_000_001)
        shouldThrow<IllegalArgumentException> {
            runBlocking { tool.execute("""{"path":"big.txt","content":"$big"}""") }
        }
    }

    test("name is write_file") {
        tool.name shouldBe "write_file"
    }

    test("riskLevel is DESTRUCTIVE") {
        tool.riskLevel shouldBe RiskLevel.DESTRUCTIVE
    }
})
