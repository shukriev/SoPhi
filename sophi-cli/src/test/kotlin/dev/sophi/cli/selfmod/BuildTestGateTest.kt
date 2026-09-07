package dev.sophi.cli.selfmod

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

private fun fakeMvn(dir: Path, body: String): List<String> {
    val script = dir.resolve("fake-mvn.sh")
    script.writeText("#!/bin/sh\n$body\n")
    script.toFile().setExecutable(true)
    return listOf(script.toString())
}

class BuildTestGateTest : FunSpec({
    test("success: creates the jar and reports success") {
        val worktree = tempdir().toPath()
        val jarDir = worktree.resolve("sophi-cli/target")
        val cmd = fakeMvn(worktree, "mkdir -p $jarDir && touch $jarDir/sophi-cli-1.0.0-SNAPSHOT.jar\necho ok")

        val result = runBuild(worktree, skipTests = false, mvnCommand = cmd)

        result.success shouldBe true
        result.jarPath!!.exists() shouldBe true
    }

    test("failure: non-zero exit reports failure with the log tail, no jarPath") {
        val worktree = tempdir().toPath()
        val cmd = fakeMvn(worktree, "echo 'BUILD FAILURE: test X failed'\nexit 1")

        val result = runBuild(worktree, skipTests = false, mvnCommand = cmd)

        result.success shouldBe false
        result.logTail shouldContain "BUILD FAILURE"
        result.jarPath shouldBe null
    }

    test("skipTests passes -DskipTests to the command") {
        val worktree = tempdir().toPath()
        val argsFile = worktree.resolve("captured-args.txt")
        val cmd = fakeMvn(worktree, "echo \"\$@\" > $argsFile")

        runBuild(worktree, skipTests = true, mvnCommand = cmd)

        argsFile.toFile().readText() shouldContain "-DskipTests"
    }

    test("logTail is capped to the last logTailLines lines") {
        val worktree = tempdir().toPath()
        val cmd = fakeMvn(worktree, "for i in \$(seq 1 200); do echo \"line \$i\"; done\nexit 1")

        val result = runBuild(worktree, skipTests = false, mvnCommand = cmd, logTailLines = 10)

        result.logTail.lines().filter { it.isNotBlank() }.size shouldBe 10
        result.logTail shouldContain "line 200"
    }
})
