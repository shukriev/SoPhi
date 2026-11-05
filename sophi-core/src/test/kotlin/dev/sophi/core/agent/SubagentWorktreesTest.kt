package dev.sophi.core.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun sh(dir: Path, vararg cmd: String): String {
    val p = ProcessBuilder(*cmd).directory(dir.toFile()).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "${cmd.toList()} failed: $out" }
    return out
}

private fun commit(dir: Path, msg: String) =
    sh(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "commit", "-qm", msg)

/** A temp repo with one commit holding `a.txt`. */
private fun repo(): Path = createTempDirectory("wt-repo").also { r ->
    sh(r, "git", "init", "-q")
    r.resolve("a.txt").writeText("a")
    sh(r, "git", "add", "-A")
    commit(r, "init")
}

private fun children(dir: Path): Long = if (dir.exists()) Files.walk(dir).use { it.count() - 1 } else 0

class SubagentWorktreesTest : FunSpec({
    fun worktrees(root: Path, home: Path = createTempDirectory("wt-home")) = SubagentWorktrees(root, home, toolsAt = { emptyList() })

    test("open in a folder that is not a repo throws and leaves nothing") {
        val home = createTempDirectory("wt-home")
        val e = shouldThrow<IllegalStateException> { worktrees(createTempDirectory("wt-plain"), home).open("worker") }
        e.message!! shouldContain "not in a git repository"
        children(home) shouldBe 0
    }

    test("open in a repo with no commit throws and leaves nothing") {
        val home = createTempDirectory("wt-home")
        val r = createTempDirectory("wt-unborn").also { sh(it, "git", "init", "-q") }
        shouldThrow<IllegalStateException> { worktrees(r, home).open("worker") }
        children(home) shouldBe 0
    }

    test("clean finish removes the worktree and its branch") {
        val r = repo()
        val wt = worktrees(r).open("worker")
        wt.path.resolve("a.txt").readText() shouldBe "a"
        wt.finish("subagent worker: nothing") shouldContain "No file changes"
        wt.path.exists() shouldBe false
        sh(r, "git", "branch", "--list", "sophi-subagent/*").trim() shouldBe ""
    }

    test("finish with a new file commits it to the branch and keeps the worktree") {
        val r = repo()
        val wt = worktrees(r).open("Code Writer")
        wt.branch shouldContain "sophi-subagent/code-writer-"
        wt.path.resolve("new.txt").writeText("hello")
        val out = wt.finish("subagent worker: write new.txt")
        sh(r, "git", "show", "${wt.branch}:new.txt") shouldBe "hello"
        wt.path.exists() shouldBe true
        out shouldContain wt.branch
        out shouldContain wt.path.toString()
        out shouldContain "git merge ${wt.branch}"
        r.resolve("new.txt").exists() shouldBe false
    }

    test("the main tree's uncommitted work is untouched") {
        val r = repo()
        r.resolve("a.txt").writeText("edited")
        r.resolve("untracked.txt").writeText("u")
        val before = sh(r, "git", "status", "--porcelain")
        val wt = worktrees(r).open("worker")
        wt.dirty shouldBe true
        wt.path.resolve("a.txt").readText() shouldBe "a"
        wt.path.resolve("a.txt").writeText("theirs")
        wt.finish("subagent worker: edit") shouldContain "uncommitted changes"
        r.resolve("a.txt").readText() shouldBe "edited"
        r.resolve("untracked.txt").readText() shouldBe "u"
        sh(r, "git", "status", "--porcelain") shouldBe before
    }

    test("a session root in a subfolder maps to the same subfolder in the worktree") {
        val r = repo()
        r.resolve("sub").createDirectories().resolve("b.txt").writeText("b")
        sh(r, "git", "add", "-A")
        commit(r, "sub")
        val wt = worktrees(r.resolve("sub")).open("worker")
        wt.root shouldBe wt.path.resolve("sub")
        wt.root.resolve("b.txt").readText() shouldBe "b"
        wt.finish("x")
    }

    test("a commit the subagent made itself keeps the worktree and branch") {
        val r = repo()
        val wt = worktrees(r).open("worker")
        wt.path.resolve("c.txt").writeText("c")
        sh(wt.path, "git", "add", "-A")
        commit(wt.path, "by the subagent")
        wt.finish("x") shouldContain wt.branch
        wt.path.exists() shouldBe true
        sh(r, "git", "show", "${wt.branch}:c.txt") shouldBe "c"
    }
})
