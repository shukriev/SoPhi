package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class RunClaudeCodeToolTest : FunSpec({
    // Writes a fake "claude" as a real, executable shell script — never invokes the real
    // claude binary. `body` is raw shell script text; Kotlin string templates ($var) inside
    // `body` are evaluated at script-generation time (baking literal paths in), while a
    // literal shell variable like $@ must be written as \$@ to survive into the script.
    fun fakeClaude(dir: Path, body: String): List<String> {
        val script = dir.resolve("fake-claude.sh")
        script.writeText("#!/bin/sh\n$body\n")
        script.toFile().setExecutable(true)
        return listOf(script.toString())
    }

    fun projectDir(): Path = createTempDirectory("run-claude-code-test")

    test("name is invoke_claude_code") {
        RunClaudeCodeTool().name shouldBe "invoke_claude_code"
    }

    test("riskLevel is always DESTRUCTIVE regardless of arguments") {
        val tool = RunClaudeCodeTool()
        tool.riskLevel("""{"project_path":"/tmp","task":"anything"}""") shouldBe RiskLevel.DESTRUCTIVE
        tool.riskLevel("""not even valid json""") shouldBe RiskLevel.DESTRUCTIVE
    }

    test("ruleVerdict is always HIGH_RISK regardless of arguments") {
        val tool = RunClaudeCodeTool()
        tool.ruleVerdict("""{"project_path":"/tmp","task":"anything"}""") shouldBe RuleVerdict.HIGH_RISK
        tool.ruleVerdict("""not even valid json""") shouldBe RuleVerdict.HIGH_RISK
    }

    test("execute returns the fake claude's stdout on success") {
        val dir = projectDir()
        val cmd = fakeClaude(dir, "echo '{\"result\":\"done\"}'")
        val tool = RunClaudeCodeTool(claudeCommand = cmd)
        val result = runBlocking { tool.execute("""{"project_path":"$dir","task":"do the thing"}""") }
        result shouldContain "\"result\":\"done\""
    }

    test("execute builds the command with --permission-mode auto and --output-format json by default") {
        val dir = projectDir()
        val argsFile = dir.resolve("captured-args.txt")
        val cmd = fakeClaude(dir, "echo \"\$@\" > $argsFile")
        val tool = RunClaudeCodeTool(claudeCommand = cmd)
        runBlocking { tool.execute("""{"project_path":"$dir","task":"do it"}""") }
        val captured = argsFile.toFile().readText()
        captured shouldContain "-p do it"
        captured shouldContain "--permission-mode auto"
        captured shouldContain "--output-format json"
        captured.contains("--allowedTools") shouldBe false
    }

    test("execute passes a custom permission_mode and allowed_tools through to the command") {
        val dir = projectDir()
        val argsFile = dir.resolve("captured-args.txt")
        val cmd = fakeClaude(dir, "echo \"\$@\" > $argsFile")
        val tool = RunClaudeCodeTool(claudeCommand = cmd)
        runBlocking {
            tool.execute(
                """{"project_path":"$dir","task":"do it","permission_mode":"plan",
                    "allowed_tools":["Bash","Edit"]}"""
            )
        }
        val captured = argsFile.toFile().readText()
        captured shouldContain "--permission-mode plan"
        captured shouldContain "--allowedTools Bash Edit"
    }

    test("execute reports a non-zero exit code") {
        val dir = projectDir()
        val cmd = fakeClaude(dir, "echo 'boom' >&2\nexit 3")
        val tool = RunClaudeCodeTool(claudeCommand = cmd)
        val result = runBlocking { tool.execute("""{"project_path":"$dir","task":"x"}""") }
        result shouldContain "Error: claude exited 3"
        result shouldContain "boom"
    }

    test("execute returns an error when the invocation times out") {
        val dir = projectDir()
        val cmd = fakeClaude(dir, "sleep 5")
        val tool = RunClaudeCodeTool(claudeCommand = cmd)
        val result = runBlocking {
            tool.execute("""{"project_path":"$dir","task":"x","timeout_seconds":1}""")
        }
        result shouldContain "timed out after 1s"
    }

    test("execute runs the process with project_path as its working directory") {
        val dir = projectDir()
        val cmd = fakeClaude(dir, "pwd")
        val tool = RunClaudeCodeTool(claudeCommand = cmd)
        val result = runBlocking { tool.execute("""{"project_path":"$dir","task":"x"}""") }
        // Resolve both sides through realpath-equivalent (toRealPath) since /tmp is a symlink
        // to /private/tmp on macOS, and the fake script's `pwd` reports the resolved path.
        result.trim() shouldBe dir.toRealPath().toString()
    }
})
