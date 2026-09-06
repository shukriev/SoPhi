package dev.sophi.cli.selfmod

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private fun runGit(dir: File, vararg args: String) {
    val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
    check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: ${p.inputStream.bufferedReader().readText()}" }
}

private fun fixtureRepo(dir: Path): Pair<Path, String> {
    val repo = dir.toFile()
    runGit(repo, "init", "-q", "-b", "main")
    runGit(repo, "config", "user.email", "test@example.invalid")
    runGit(repo, "config", "user.name", "Test")
    dir.resolve("README.md").writeText("hello\n")
    runGit(repo, "add", "README.md")
    runGit(repo, "commit", "-q", "-m", "initial")
    val process = ProcessBuilder(listOf("git", "rev-parse", "HEAD")).directory(repo).start()
    val sha = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    return dir to sha
}

class DiffScanGateTest : FunSpec({
    test("Empty when there is no diff at all") {
        val (repo, sha) = fixtureRepo(tempdir().toPath())

        scanDiff(repo, sha) shouldBe DiffScanResult.Empty
    }

    test("Pass for a small, non-denylisted change") {
        val (repo, sha) = fixtureRepo(tempdir().toPath())
        repo.resolve("src.txt").writeText("a change\n")
        runGit(repo.toFile(), "add", "src.txt")
        runGit(repo.toFile(), "commit", "-q", "-m", "small change")

        scanDiff(repo, sha) shouldBe DiffScanResult.Pass
    }

    test("Denylisted when the diff touches .github/workflows") {
        val (repo, sha) = fixtureRepo(tempdir().toPath())
        repo.resolve(".github/workflows").createDirectories()
        repo.resolve(".github/workflows/ci.yml").writeText("name: ci\n")
        runGit(repo.toFile(), "add", ".github")
        runGit(repo.toFile(), "commit", "-q", "-m", "touch ci")

        scanDiff(repo, sha) shouldBe DiffScanResult.Denylisted(".github/workflows/ci.yml")
    }

    test("TooLarge when the diff exceeds maxChangedLines") {
        val (repo, sha) = fixtureRepo(tempdir().toPath())
        repo.resolve("big.txt").writeText((1..20).joinToString("\n") { "line $it" })
        runGit(repo.toFile(), "add", "big.txt")
        runGit(repo.toFile(), "commit", "-q", "-m", "big change")

        val result = scanDiff(repo, sha, maxChangedLines = 5)

        result shouldBe DiffScanResult.TooLarge(20, 5)
    }
})
