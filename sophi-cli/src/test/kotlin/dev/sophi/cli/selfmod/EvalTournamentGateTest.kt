package dev.sophi.cli.selfmod

import dev.sophi.cli.ResolvedProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private fun fakeJava(dir: Path, body: String): List<String> {
    val script = dir.resolve("fake-java.sh")
    script.writeText("#!/bin/sh\n$body\n")
    script.toFile().setExecutable(true)
    return listOf(script.toString())
}

private fun makeEvalsDir(dir: Path): Path {
    val evalsDir = dir.resolve("evals/coding")
    evalsDir.createDirectories()
    evalsDir.resolve("case-1.yaml").writeText(
        "id: case-1\ngoalPrompt: do a thing\ncheck:\n  command: \"true\"\n"
    )
    return dir.resolve("evals")
}

class EvalTournamentGateTest : FunSpec({
    test("skips when no eval cases exist") {
        val dir = tempdir().toPath()

        val outcome = runEvalGate(
            baselineJar = dir.resolve("baseline.jar"), challengerJar = dir.resolve("challenger.jar"),
            evalsDir = dir.resolve("no-such-evals-dir"), configVersionId = "default",
            versioningHome = dir.resolve("versioning"), provider = ResolvedProvider("claude", null, "m", 200_000, null, emptyList()),
            apiKeyOption = null
        )

        outcome.skipped shouldBe true
    }

    test("accepts a challenger that clearly improves with no regression") {
        val dir = tempdir().toPath()
        val evalsDir = makeEvalsDir(dir)
        val cmd = fakeJava(
            dir,
            """
            case "${'$'}2" in
              *baseline*) echo "headline=0.50  totalCases=4  configVersion=default"; echo "  coding: 0.50" ;;
              *challenger*) echo "headline=0.90  totalCases=4  configVersion=default"; echo "  coding: 0.90" ;;
            esac
            """.trimIndent()
        )

        val outcome = runEvalGate(
            baselineJar = dir.resolve("baseline.jar"), challengerJar = dir.resolve("challenger.jar"),
            evalsDir = evalsDir, configVersionId = "default", versioningHome = dir.resolve("versioning"),
            provider = ResolvedProvider("claude", null, "m", 200_000, null, emptyList()), apiKeyOption = null,
            runsPerSide = 1, javaCommand = cmd
        )

        outcome.skipped shouldBe false
        outcome.result!!.accepted shouldBe true
    }

    test("rejects a challenger that regresses a category") {
        val dir = tempdir().toPath()
        val evalsDir = makeEvalsDir(dir)
        val cmd = fakeJava(
            dir,
            """
            case "${'$'}2" in
              *baseline*) echo "headline=0.80  totalCases=4  configVersion=default"; echo "  coding: 0.90" ;;
              *challenger*) echo "headline=0.85  totalCases=4  configVersion=default"; echo "  coding: 0.10" ;;
            esac
            """.trimIndent()
        )

        val outcome = runEvalGate(
            baselineJar = dir.resolve("baseline.jar"), challengerJar = dir.resolve("challenger.jar"),
            evalsDir = evalsDir, configVersionId = "default", versioningHome = dir.resolve("versioning"),
            provider = ResolvedProvider("claude", null, "m", 200_000, null, emptyList()), apiKeyOption = null,
            runsPerSide = 1, javaCommand = cmd
        )

        outcome.result!!.accepted shouldBe false
    }

    test("the key reaches the eval child through SOPHI_API_KEY, never its command line") {
        val dir = tempdir().toPath()
        val evalsDir = makeEvalsDir(dir)
        val seen = dir.resolve("seen.txt")
        val cmd = fakeJava(dir, """echo "args=${'$'}* env=${'$'}SOPHI_API_KEY" >> $seen; echo "headline=0.50  totalCases=4  configVersion=default"""")

        runEvalGate(
            baselineJar = dir.resolve("baseline.jar"), challengerJar = dir.resolve("challenger.jar"),
            evalsDir = evalsDir, configVersionId = "default", versioningHome = dir.resolve("versioning"),
            provider = ResolvedProvider("openai-compat", "http://x/v1", "m", 8192, null, emptyList()), apiKeyOption = "s3cret",
            runsPerSide = 1, javaCommand = cmd
        )

        seen.toFile().readLines().forEach { line ->
            line.substringBefore(" env=").contains("s3cret") shouldBe false
            line.substringAfter(" env=") shouldBe "s3cret"
            line shouldContain "--provider openai-compat --model m --base-url http://x/v1"
            line shouldNotContain "--context-window-tokens"
        }
    }
})
