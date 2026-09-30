package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class BashToolTest : FunSpec({
    lateinit var root: Path
    lateinit var tool: BashTool

    beforeTest {
        root = createTempDirectory("sophi-bash-test")
        tool = BashTool(root)
    }

    // No input is ever fed to a command, so a command that reads stdin (cat, input(), a git
    // commit without -m) used to block on an open pipe until the timeout.
    test("execute() gives the command a closed stdin instead of an open pipe") {
        val result = runBlocking { tool.execute("""{"command":"cat; echo stdin-closed","timeoutSeconds":5}""") }
        result shouldContain "stdin-closed"
    }

    test("execute() returns stdout for a simple command") {
        val result = runBlocking { tool.execute("""{"command":"echo hello"}""") }
        result shouldContain "hello"
    }

    test("execute() runs in the working directory root") {
        root.resolve("marker.txt").writeText("x")
        val result = runBlocking { tool.execute("""{"command":"ls"}""") }
        result shouldContain "marker.txt"
    }

    test("execute() reports a non-zero exit code") {
        val result = runBlocking { tool.execute("""{"command":"exit 3"}""") }
        result shouldContain "Command exited with code 3"
    }

    test("execute() truncates output larger than the cap") {
        val result = runBlocking { tool.execute("""{"command":"yes x | head -n 60000"}""") }
        result shouldContain "output truncated"
    }

    test("execute() returns an error when the command times out") {
        val result = runBlocking { tool.execute("""{"command":"sleep 5","timeoutSeconds":1}""") }
        result shouldContain "timed out after 1s"
    }

    test("name is bash") {
        tool.name shouldBe "bash"
    }

    test("riskLevel is CAUTION for a plain read-only command") {
        tool.riskLevel("""{"command":"git status"}""") shouldBe RiskLevel.CAUTION
    }

    test("riskLevel is CAUTION for ls, cat, and git log/diff/show") {
        tool.riskLevel("""{"command":"ls -la"}""") shouldBe RiskLevel.CAUTION
        tool.riskLevel("""{"command":"cat file.txt"}""") shouldBe RiskLevel.CAUTION
        tool.riskLevel("""{"command":"git log"}""") shouldBe RiskLevel.CAUTION
        tool.riskLevel("""{"command":"git diff"}""") shouldBe RiskLevel.CAUTION
        tool.riskLevel("""{"command":"git show HEAD"}""") shouldBe RiskLevel.CAUTION
    }

    test("riskLevel is DESTRUCTIVE for a command not on the read-only prefix list") {
        tool.riskLevel("""{"command":"rm -rf /tmp/x"}""") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("riskLevel is DESTRUCTIVE when a read-only-looking command is chained with shell metacharacters") {
        tool.riskLevel("""{"command":"git status; rm -rf /"}""") shouldBe RiskLevel.DESTRUCTIVE
        tool.riskLevel("""{"command":"ls && rm file"}""") shouldBe RiskLevel.DESTRUCTIVE
        tool.riskLevel("""{"command":"cat $(danger)"}""") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("riskLevel is DESTRUCTIVE when arguments cannot be parsed") {
        tool.riskLevel("not json") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("ruleVerdict is HIGH_RISK for rm -rf") {
        tool.ruleVerdict("""{"command":"rm -rf /tmp/x"}""") shouldBe RuleVerdict.HIGH_RISK
    }

    test("ruleVerdict is HIGH_RISK for sudo") {
        tool.ruleVerdict("""{"command":"sudo apt-get install foo"}""") shouldBe RuleVerdict.HIGH_RISK
    }

    test("ruleVerdict is HIGH_RISK for a forced git push") {
        tool.ruleVerdict("""{"command":"git push --force origin main"}""") shouldBe RuleVerdict.HIGH_RISK
    }

    test("ruleVerdict is LOW_RISK for rm of a single file under a scratch path") {
        tool.ruleVerdict("""{"command":"rm /tmp/scratch-file.txt"}""") shouldBe RuleVerdict.LOW_RISK
    }

    test("ruleVerdict is UNKNOWN for rm of a single file outside a scratch path") {
        tool.ruleVerdict("""{"command":"rm important-file.txt"}""") shouldBe RuleVerdict.UNKNOWN
    }

    test("ruleVerdict is UNKNOWN for an ordinary command with no matching rule") {
        tool.ruleVerdict("""{"command":"npm install"}""") shouldBe RuleVerdict.UNKNOWN
    }

    // An unparseable call can't run: execute() decodes the same type and throws first. Prompting
    // for it only swallowed a scripted turn and hid the real parse error from the model. riskLevel
    // still fails closed (the ADR-027 grants probe depends on that); ruleVerdict no longer does.
    test("ruleVerdict lets an unparseable call through to fail with its parse error") {
        tool.ruleVerdict("not json") shouldBe RuleVerdict.LOW_RISK
        shouldThrowAny { runBlocking { tool.execute("not json") } }
    }
})
