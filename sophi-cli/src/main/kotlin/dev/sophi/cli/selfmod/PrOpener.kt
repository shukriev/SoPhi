package dev.sophi.cli.selfmod

import java.nio.file.Path

data class PrOpenResult(val success: Boolean, val prUrl: String?, val log: String)

class PrOpener(
    private val gitCommand: List<String> = listOf("git"),
    private val ghCommand: List<String> = listOf("gh")
) {
    fun push(worktree: Path, branch: String): Boolean {
        val process = ProcessBuilder(gitCommand + listOf("push", "origin", branch))
            .directory(worktree.toFile())
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText()
        return process.waitFor() == 0
    }

    fun openPr(worktree: Path, title: String, body: String): PrOpenResult {
        val process = ProcessBuilder(
            ghCommand + listOf("pr", "create", "--title", title, "--body", body, "--label", "self-improvement")
        ).directory(worktree.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        val prUrl = output.lines().map { it.trim() }.firstOrNull { it.startsWith("https://") }
        return PrOpenResult(success = exitCode == 0 && prUrl != null, prUrl = prUrl, log = output)
    }
}
