package dev.sophi.cli.selfmod

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

private fun runGit(dir: File, vararg args: String) {
    val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
    check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: ${p.inputStream.bufferedReader().readText()}" }
}

private fun fixtureRepo(dir: Path): Path {
    val repo = dir.toFile()
    runGit(repo, "init", "-q", "-b", "main")
    runGit(repo, "config", "user.email", "test@example.invalid")
    runGit(repo, "config", "user.name", "Test")
    dir.resolve("README.md").writeText("hello\n")
    runGit(repo, "add", "README.md")
    runGit(repo, "commit", "-q", "-m", "initial")
    return dir
}

class ShadowPrWorktreeManagerTest : FunSpec({
    test("branchPointSha returns main's current commit sha") {
        val repo = fixtureRepo(tempdir().toPath())
        val manager = ShadowPrWorktreeManager(repo)

        val sha = manager.branchPointSha()

        sha.length shouldBe 40
    }

    test("create checks out a new branch worktree off the given sha") {
        val repo = fixtureRepo(tempdir().toPath())
        val manager = ShadowPrWorktreeManager(repo)
        val sha = manager.branchPointSha()
        val worktreesParent = tempdir().toPath()

        val wt = manager.create(worktreesParent, "prop_1", "fix-thing", sha)

        wt.path.resolve("README.md").exists() shouldBe true
        wt.branch shouldStartWith "selfmod/prop_1-"
        wt.branchPointSha shouldBe sha
    }

    test("createDetached checks out the given sha with no branch") {
        val repo = fixtureRepo(tempdir().toPath())
        val manager = ShadowPrWorktreeManager(repo)
        val sha = manager.branchPointSha()
        val worktreesParent = tempdir().toPath()

        val path = manager.createDetached(worktreesParent, sha, "prop_1-baseline")

        path.resolve("README.md").exists() shouldBe true
    }

    test("remove deletes a worktree created by create") {
        val repo = fixtureRepo(tempdir().toPath())
        val manager = ShadowPrWorktreeManager(repo)
        val sha = manager.branchPointSha()
        val worktreesParent = tempdir().toPath()
        val wt = manager.create(worktreesParent, "prop_1", "fix-thing", sha)

        manager.remove(wt.path)

        wt.path.exists() shouldBe false
    }
})
