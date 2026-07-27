package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
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

    test("riskLevel is DESTRUCTIVE") {
        tool.riskLevel("{}") shouldBe RiskLevel.DESTRUCTIVE
    }
})
