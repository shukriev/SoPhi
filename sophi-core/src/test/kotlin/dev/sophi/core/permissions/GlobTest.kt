package dev.sophi.core.permissions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GlobTest : FunSpec({
    test("globMatches") {
        globMatches("git status*", "git status") shouldBe true
        globMatches("git status*", "git status -s") shouldBe true
        globMatches("git status*", "git statu") shouldBe false
        globMatches("*.env", "a/b/.env") shouldBe true
        globMatches("ls \\*.kt", "ls *.kt") shouldBe true
        globMatches("ls \\*.kt", "ls a.kt") shouldBe false
        globMatches("a\\\\b", "a\\b") shouldBe true
        globMatches("GIT*", "git status") shouldBe false
        globMatches("a.c", "abc") shouldBe false
    }

    test("globEscape matches only the literal text") {
        globMatches(globEscape("ls *.kt"), "ls a.kt") shouldBe false
        globMatches(globEscape("ls *.kt"), "ls *.kt") shouldBe true
        globMatches(globEscape("a\\b"), "a\\b") shouldBe true
    }
})
