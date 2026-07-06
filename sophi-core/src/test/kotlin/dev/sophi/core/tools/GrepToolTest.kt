package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
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
        val buildParent = createTempDirectory("build")
        val projectRoot = buildParent.resolve("project")
        projectRoot.createDirectory()

        val toolWithBuildAncestor = GrepTool(projectRoot)
        projectRoot.resolve("a.txt").writeText("target content\n")

        val result = runBlocking { toolWithBuildAncestor.execute("""{"pattern":"target"}""") }

        // Should find the file even though the absolute path contains "build" ancestor
        result shouldContain "a.txt"
        (result.contains("No matches found")) shouldBe false
    }
})
