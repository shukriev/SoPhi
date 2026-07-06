package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class GlobToolTest : FunSpec({
    lateinit var root: Path
    lateinit var tool: GlobTool

    beforeTest {
        root = createTempDirectory("sophi-glob-test")
        tool = GlobTool(root)
    }

    test("execute() finds files matching a top-level pattern") {
        root.resolve("a.kt").writeText("x")
        root.resolve("b.md").writeText("x")
        val result = runBlocking { tool.execute("""{"pattern":"*.kt"}""") }
        result shouldBe "a.kt"
    }

    test("execute() finds files matching a recursive pattern") {
        root.resolve("src/main").createDirectories()
        root.resolve("src/main/App.kt").writeText("x")
        val result = runBlocking { tool.execute("""{"pattern":"**/*.kt"}""") }
        result shouldBe "src/main/App.kt"
    }

    test("execute() returns results sorted alphabetically") {
        root.resolve("b.kt").writeText("x")
        root.resolve("a.kt").writeText("x")
        val result = runBlocking { tool.execute("""{"pattern":"*.kt"}""") }
        result shouldBe "a.kt\nb.kt"
    }

    test("execute() skips .git and build directories") {
        root.resolve(".git").createDirectory()
        root.resolve(".git/HEAD.kt").writeText("x")
        root.resolve("src").createDirectory()
        root.resolve("src/real.kt").writeText("x")
        val result = runBlocking { tool.execute("""{"pattern":"**/*.kt"}""") }
        result shouldBe "src/real.kt"
    }

    test("execute() returns 'No files found' when nothing matches") {
        val result = runBlocking { tool.execute("""{"pattern":"*.nonexistent"}""") }
        result shouldBe "No files found"
    }

    test("name is glob") {
        tool.name shouldBe "glob"
    }
})
