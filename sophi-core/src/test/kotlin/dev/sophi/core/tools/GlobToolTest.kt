package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.setPosixFilePermissions
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

    // Regression: a broad search rooted at a real home directory routinely crosses at least
    // one permission-restricted subtree (e.g. macOS TCC-protected folders). Files.walk() aborts
    // the whole search the moment it hits one; matches outside that subtree must still come back.
    test("execute() skips an unreadable subdirectory instead of aborting the whole search") {
        val locked = root.resolve("locked").also { it.createDirectory() }
        locked.resolve("secret.kt").writeText("x")
        root.resolve("visible.kt").writeText("x")
        try {
            locked.setPosixFilePermissions(PosixFilePermissions.fromString("---------"))
            val result = runBlocking { tool.execute("""{"pattern":"*.kt"}""") }
            result shouldBe "visible.kt"
        } finally {
            locked.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    // Regression: "path" is documented as scoping the search into a subdirectory, so patterns
    // should be written as if already inside it (e.g. "*.txt", not "subdir/*.txt"). The matcher
    // was being applied to paths relative to the outer root regardless of "path", so a bare
    // top-level pattern like "*.txt" never matched anything once a subdirectory was scoped in.
    test("execute() matches patterns relative to the scoped 'path', not the outer root") {
        root.resolve("transcribe").createDirectory()
        root.resolve("transcribe/Benni.txt").writeText("x")
        val result = runBlocking { tool.execute("""{"path":"transcribe","pattern":"*.txt"}""") }
        result shouldBe "transcribe/Benni.txt"
    }
})
