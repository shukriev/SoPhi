package dev.sophi.cli.selfmod

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

data class ShadowPrWorktree(val path: Path, val branch: String, val branchPointSha: String)

class ShadowPrWorktreeManager(
    private val repoRoot: Path,
    private val gitCommand: List<String> = listOf("git")
) {
    private fun run(vararg args: String, workingDir: File = repoRoot.toFile()): String {
        val process = ProcessBuilder(gitCommand + args)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    fun branchPointSha(baseBranch: String = "main"): String = run("rev-parse", baseBranch)

    private fun slug(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    fun create(worktreeParent: Path, proposalId: String, titleSlug: String, branchPointSha: String): ShadowPrWorktree {
        Files.createDirectories(worktreeParent)
        val branch = "selfmod/$proposalId-${slug(titleSlug)}"
        val path = worktreeParent.resolve(proposalId)
        run("worktree", "add", path.toString(), "-b", branch, branchPointSha)
        return ShadowPrWorktree(path, branch, branchPointSha)
    }

    fun createDetached(worktreeParent: Path, sha: String, name: String): Path {
        Files.createDirectories(worktreeParent)
        val path = worktreeParent.resolve(name)
        run("worktree", "add", "--detach", path.toString(), sha)
        return path
    }

    fun remove(path: Path) {
        run("worktree", "remove", "--force", path.toString())
    }
}
