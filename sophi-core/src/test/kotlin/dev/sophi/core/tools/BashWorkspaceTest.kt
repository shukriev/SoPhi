package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class BashWorkspaceTest : FunSpec({
    val root = Path.of("/Users/me/.sophi/workspace")
    val home = "/Users/me"

    test("commands that stay in the workspace") {
        listOf(
            "ls", "cat notes.md", "ls -la ./reports", "python3 script.py > out.txt", "cat /tmp/scratch.txt",
            "grep -r TODO . 2>/dev/null", "/opt/homebrew/bin/node build.js", "cd sub && ls", "cat reports/../notes.md",
            "cat /Users/me/.sophi/workspace/notes.md", "echo 'a/b ~ literal'",
        ).forEach { cmd -> withClue(cmd) { bashReachesOutside(cmd, root, home) shouldBe null } }
    }

    test("commands that reach outside it, with the path they reach") {
        listOf(
            "cat ~/.ssh/id_rsa" to "~/.ssh/id_rsa",
            "ls /Users/me/Documents" to "/Users/me/Documents",
            "cat ../../.zshrc" to "../../.zshrc",
            "cd / && ls" to "/",
            "cd ~ && cat .netrc" to "~",
            "tar czf out.tgz /etc" to "/etc",
            "cp report.pdf \$HOME/Desktop/" to "\$HOME/Desktop/",
        ).forEach { (cmd, path) -> withClue(cmd) { bashReachesOutside(cmd, root, home) shouldBe path } }
    }

    test("a read-only command outside the workspace is DESTRUCTIVE, so Freedom asks; inside it stays CAUTION") {
        val tool = BashTool(root)
        tool.riskLevel("""{"command":"cat ~/.ssh/id_rsa"}""") shouldBe RiskLevel.DESTRUCTIVE
        tool.riskLevel("""{"command":"ls /Users/me/Documents"}""") shouldBe RiskLevel.DESTRUCTIVE
        tool.riskLevel("""{"command":"cat notes.md"}""") shouldBe RiskLevel.CAUTION
        tool.riskLevel("""{"command":"ls"}""") shouldBe RiskLevel.CAUTION
    }
})
