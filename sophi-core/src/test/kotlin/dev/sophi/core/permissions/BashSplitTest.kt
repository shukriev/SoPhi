package dev.sophi.core.permissions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BashSplitTest : FunSpec({
    val cases: List<Pair<String, List<String>?>> = listOf(
        "git status && rm -rf x" to listOf("git status", "rm -rf x"),
        "a; b || c | d & e" to listOf("a", "b", "c", "d", "e"),
        "a\nb" to listOf("a", "b"),
        "echo \"x; y\"" to listOf("echo \"x; y\""),
        "echo 'a && b'" to listOf("echo 'a && b'"),
        "echo \"\$(rm -rf x)\"" to listOf("echo \"\$(rm -rf x)\"", "rm -rf x"),
        "echo `rm x`" to listOf("echo `rm x`", "rm x"),
        "echo '\$(rm x)'" to listOf("echo '\$(rm x)'"),
        "(cd a; rm b)" to listOf("cd a", "rm b"),
        "{ a; b; }" to listOf("a", "b"),
        "if git status; then rm x; fi" to listOf("git status", "rm x"),
        "FOO=1 BAR=\"a b\" rm x" to listOf("rm x"),
        "git   status" to listOf("git status"),
        "diff <(ls a) <(ls b)" to listOf("diff <(ls a) <(ls b)", "ls a", "ls b"),
        "" to emptyList(),
        "  ;  " to emptyList(),
        "echo \"unterminated" to null,
        "echo \$(ls" to null,
        "cat <<EOF\nx\nEOF" to null,
    )

    cases.forEach { (input, parts) ->
        test("split ${input.replace("\n", "\\n")}") {
            splitBashCommand(input)?.map { it.text } shouldBe parts
        }
    }

    test("redirects flag") {
        splitBashCommand("git status > out.txt") shouldBe listOf(BashPart("git status > out.txt", true))
        splitBashCommand("git status 2>&1 >/dev/null")!!.single().redirects shouldBe false
        splitBashCommand("echo \"a > b\"")!!.single().redirects shouldBe false
        splitBashCommand("echo a >> f; ls")!!.map { it.redirects } shouldBe listOf(true, false)
    }
})
