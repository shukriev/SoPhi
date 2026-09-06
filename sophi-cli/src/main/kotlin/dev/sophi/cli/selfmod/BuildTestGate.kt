package dev.sophi.cli.selfmod

import java.nio.file.Path
import kotlin.io.path.exists

data class BuildResult(val success: Boolean, val logTail: String, val jarPath: Path?)

fun runBuild(
    worktree: Path,
    skipTests: Boolean,
    mvnCommand: List<String> = listOf("mvn"),
    jarRelativePath: Path = Path.of("sophi-cli", "target", "sophi-cli-1.0.0-SNAPSHOT.jar"),
    logTailLines: Int = 100
): BuildResult {
    val args = buildList {
        addAll(listOf("-pl", "sophi-cli", "-am", "package"))
        if (skipTests) add("-DskipTests")
    }
    val process = ProcessBuilder(mvnCommand + args)
        .directory(worktree.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    val logTail = output.lines().filter { it.isNotBlank() }.takeLast(logTailLines).joinToString("\n")
    val jarPath = worktree.resolve(jarRelativePath)

    return if (exitCode == 0 && jarPath.exists()) {
        BuildResult(success = true, logTail = logTail, jarPath = jarPath)
    } else {
        BuildResult(success = false, logTail = logTail, jarPath = null)
    }
}
