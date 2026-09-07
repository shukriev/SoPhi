package dev.sophi.cli.selfmod

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.writeText

private fun fakeExecutable(dir: Path, name: String, body: String): List<String> {
    val script = dir.resolve(name)
    script.writeText("#!/bin/sh\n$body\n")
    script.toFile().setExecutable(true)
    return listOf(script.toString())
}

class PrOpenerTest : FunSpec({
    test("push returns true on a zero exit") {
        val dir = tempdir().toPath()
        val git = fakeExecutable(dir, "fake-git.sh", "exit 0")
        val opener = PrOpener(gitCommand = git)

        opener.push(dir, "selfmod/x") shouldBe true
    }

    test("push returns false on a non-zero exit") {
        val dir = tempdir().toPath()
        val git = fakeExecutable(dir, "fake-git.sh", "exit 1")
        val opener = PrOpener(gitCommand = git)

        opener.push(dir, "selfmod/x") shouldBe false
    }

    test("openPr parses the PR url from gh's stdout on success") {
        val dir = tempdir().toPath()
        val gh = fakeExecutable(dir, "fake-gh.sh", "echo https://github.com/shukriev/SoPhi/pull/42")
        val opener = PrOpener(ghCommand = gh)

        val result = opener.openPr(dir, "title", "body")

        result.success shouldBe true
        result.prUrl shouldBe "https://github.com/shukriev/SoPhi/pull/42"
    }

    test("openPr reports failure with the log on a non-zero exit") {
        val dir = tempdir().toPath()
        val gh = fakeExecutable(dir, "fake-gh.sh", "echo 'not authenticated' >&2\nexit 1")
        val opener = PrOpener(ghCommand = gh)

        val result = opener.openPr(dir, "title", "body")

        result.success shouldBe false
        result.prUrl shouldBe null
    }
})
