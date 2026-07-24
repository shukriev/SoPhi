package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

class GrepToolTest : FunSpec({
    lateinit var root: Path
    lateinit var tool: GrepTool

    beforeTest {
        root = createTempDirectory("sophi-grep-test")
        tool = GrepTool(root)
    }

    test("execute() finds a matching line with file and line number") {
        root.resolve("a.txt").writeText("hello\nworld\nfoo bar\n")
        val result = runBlocking { tool.execute("""{"pattern":"wor.d"}""") }
        result shouldContain "a.txt:2: world"
    }

    test("execute() returns 'No matches found' when nothing matches") {
        root.resolve("a.txt").writeText("hello\n")
        val result = runBlocking { tool.execute("""{"pattern":"zzz"}""") }
        result shouldBe "No matches found"
    }

    test("execute() filters by filePattern") {
        root.resolve("a.kt").writeText("target\n")
        root.resolve("b.md").writeText("target\n")
        val result = runBlocking { tool.execute("""{"pattern":"target","filePattern":"*.kt"}""") }
        result shouldContain "a.kt"
        (result.contains("b.md")) shouldBe false
    }

    test("execute() skips .git and build directories") {
        root.resolve(".git").createDirectory()
        root.resolve(".git/config").writeText("target\n")
        root.resolve("build").createDirectory()
        root.resolve("build/out.txt").writeText("target\n")
        root.resolve("real.txt").writeText("target\n")
        val result = runBlocking { tool.execute("""{"pattern":"target"}""") }
        result shouldContain "real.txt"
        result.lines().size shouldBe 1
    }

    test("execute() truncates and notes truncation past maxResults") {
        root.resolve("a.txt").writeText((1..5).joinToString("\n") { "target" })
        val result = runBlocking { tool.execute("""{"pattern":"target","maxResults":2}""") }
        result shouldContain "more matches truncated"
        result.lines().size shouldBe 3 // 2 matches + truncation note
    }

    test("name is grep") {
        tool.name shouldBe "grep"
    }

    test("riskLevel is SAFE") {
        tool.riskLevel shouldBe RiskLevel.SAFE
    }

    test("execute() should find matches even when root's ancestor is a skip directory name") {
        // Regression test: root's parent is literally named "build"
        // The old code checked absolute path components, so all files would be filtered out.
        // The fixed code checks relative path components only.
        val tempParent = createTempDirectory("sophi-grep-ancestor-test")
        val buildParent = tempParent.resolve("build").also { it.createDirectory() }
        val projectRoot = buildParent.resolve("project").also { it.createDirectory() }

        val toolWithBuildAncestor = GrepTool(projectRoot)
        projectRoot.resolve("a.txt").writeText("target content\n")

        val result = runBlocking { toolWithBuildAncestor.execute("""{"pattern":"target"}""") }

        // Should find the file even though the absolute path contains "build" ancestor
        result shouldContain "a.txt"
    }

    // Regression: same Files.walk()-aborts-on-first-permission-error issue as GlobTool.
    test("execute() skips an unreadable subdirectory instead of aborting the whole search") {
        val locked = root.resolve("locked").also { it.createDirectory() }
        locked.resolve("secret.txt").writeText("target\n")
        root.resolve("visible.txt").writeText("target\n")
        try {
            locked.setPosixFilePermissions(PosixFilePermissions.fromString("---------"))
            val result = runBlocking { tool.execute("""{"pattern":"target"}""") }
            result shouldContain "visible.txt"
        } finally {
            locked.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }
})
