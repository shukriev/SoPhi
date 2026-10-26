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
        "! rm x" to listOf("rm x"),
        "echo \$'\\'' ; rm -f x ; echo '\\'" to null,
        "echo \$(case a in a) rm -f x;; esac)" to null,
        "a)" to null,
        "function f { rm -f x; }; f" to listOf("function f", "rm -f x", "f"),
        ">/dev/null rm -f x" to listOf("rm -f x"),
        "> out.txt rm -f x" to listOf("rm -f x"),
        "2>&1 rm x" to listOf("rm x"),
        "&>/dev/null rm -f x" to listOf("rm -f x"),
        "&>> log rm -f x" to listOf("rm -f x"),
        "" to emptyList(),
        "FOO=1" to emptyList(),
        "> ~/.bashrc" to listOf("> ~/.bashrc"),
        "ls; > ~/.bashrc" to listOf("ls", "> ~/.bashrc"),
        ">/dev/null" to listOf(">/dev/null"),
        "  ;  " to emptyList(),
        "echo \"unterminated" to null,
        "echo \$(ls" to null,
        "cat <<EOF\nx\nEOF" to null,
        // bash un-escapes \` \$ \\ inside backticks before running the body
        "echo `echo \\`rm x\\``" to listOf("echo `echo \\`rm x\\``", "echo `rm x`", "rm x"),
        "echo `echo \\\$(rm x)`" to listOf("echo `echo \\\$(rm x)`", "echo \$(rm x)", "rm x"),
        // a quoted, escaped or expanded command word can't be matched: unknown
        "\\rm x" to null,
        "\"rm\" x" to null,
        "r''m x" to null,
        "\$RM x" to null,
        "{rm,x}" to null,
        "{rm,x} y" to null,
        "`echo rm` x" to null,
        "ls; \\rm x" to null,
        "time -p rm x" to listOf("rm x"),
        "time rm x" to listOf("rm x"),
        "git commit -m \"msg\"" to listOf("git commit -m \"msg\""),
        "echo \$HOME" to listOf("echo \$HOME"),
        "grep 'x' f" to listOf("grep 'x' f"),
        "cd \"\$DIR\" && ls" to listOf("cd \"\$DIR\"", "ls"),
    )

    cases.forEach { (input, parts) ->
        test("split ${input.replace("\n", "\\n")}") {
            splitBashCommand(input)?.map { it.text } shouldBe parts
        }
    }

    test("redirects flag") {
        splitBashCommand("> ~/.bashrc")!!.single().redirects shouldBe true
        splitBashCommand(">/dev/null")!!.single().redirects shouldBe false
        splitBashCommand("true >&1x")!!.single().redirects shouldBe true
        splitBashCommand("true >&2")!!.single().redirects shouldBe false
        splitBashCommand("> out.txt rm x")!!.single().redirects shouldBe true
        splitBashCommand("git status > out.txt") shouldBe listOf(BashPart("git status > out.txt", true))
        splitBashCommand("git status 2>&1 >/dev/null")!!.single().redirects shouldBe false
        splitBashCommand("echo \"a > b\"")!!.single().redirects shouldBe false
        splitBashCommand("echo a >> f; ls")!!.map { it.redirects } shouldBe listOf(true, false)
    }

    test("assigns flag") {
        splitBashCommand("FOO=1 git status") shouldBe listOf(BashPart("git status", redirects = false, assigns = true))
        splitBashCommand("git status; LD_PRELOAD=/tmp/x.so ls")!!.map { it.assigns } shouldBe listOf(false, true)
        splitBashCommand("git status FOO=1")!!.single().assigns shouldBe false
    }
})
