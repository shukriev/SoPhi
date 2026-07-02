package dev.sophi.core.tools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class FileReadToolTest : FunSpec({
    lateinit var root: Path
    lateinit var tool: FileReadTool

    beforeTest {
        root = createTempDirectory("sophi-file-read-test")
        tool = FileReadTool(root)
    }

    test("execute() returns file contents for an existing file") {
        root.resolve("hello.txt").writeText("hello world")
        val result = runBlocking { tool.execute("""{"path":"hello.txt"}""") }
        result shouldBe "hello world"
    }

    test("execute() throws FileNotFoundException for a missing file") {
        shouldThrow<FileNotFoundException> {
            runBlocking { tool.execute("""{"path":"missing.txt"}""") }
        }
    }

    test("execute() throws FileNotFoundException when path is a directory") {
        root.resolve("subdir").createDirectory()
        shouldThrow<FileNotFoundException> {
            runBlocking { tool.execute("""{"path":"subdir"}""") }
        }
    }

    test("execute() throws IllegalArgumentException when path escapes root") {
        shouldThrow<IllegalArgumentException> {
            runBlocking { tool.execute("""{"path":"../outside.txt"}""") }
        }
    }

    test("execute() throws IllegalArgumentException when file exceeds the size cap") {
        root.resolve("big.txt").writeText("x".repeat(1_000_001))
        shouldThrow<IllegalArgumentException> {
            runBlocking { tool.execute("""{"path":"big.txt"}""") }
        }
    }

    test("name is read_file") {
        tool.name shouldBe "read_file"
    }
})
