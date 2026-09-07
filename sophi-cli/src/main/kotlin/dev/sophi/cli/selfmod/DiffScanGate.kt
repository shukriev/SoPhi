package dev.sophi.cli.selfmod

import java.nio.file.Path

sealed class DiffScanResult {
    object Pass : DiffScanResult()
    data class Denylisted(val path: String) : DiffScanResult()
    data class TooLarge(val changedLines: Int, val cap: Int) : DiffScanResult()
    object Empty : DiffScanResult()
}

private val DENYLIST_PREFIXES = listOf(".github/workflows/", ".git/")

fun scanDiff(
    worktree: Path,
    branchPointSha: String,
    maxChangedLines: Int = 500,
    gitCommand: List<String> = listOf("git")
): DiffScanResult {
    fun git(vararg args: String): String {
        val process = ProcessBuilder(gitCommand + args)
            .directory(worktree.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    val changedFiles = git("diff", "--name-only", "$branchPointSha..HEAD")
        .lines().filter { it.isNotBlank() }
    if (changedFiles.isEmpty()) return DiffScanResult.Empty

    changedFiles.forEach { path ->
        DENYLIST_PREFIXES.forEach { prefix -> if (path.startsWith(prefix)) return DiffScanResult.Denylisted(path) }
    }

    val numstat = git("diff", "--numstat", "$branchPointSha..HEAD").lines().filter { it.isNotBlank() }
    val changedLines = numstat.sumOf { line ->
        val parts = line.split("\t")
        (parts.getOrNull(0)?.toIntOrNull() ?: 0) + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }
    if (changedLines > maxChangedLines) return DiffScanResult.TooLarge(changedLines, maxChangedLines)

    return DiffScanResult.Pass
}
