package dev.sophi.sdk

import dev.sophi.mcp.config.McpServerConfig
import dev.sophi.mcp.config.McpTransport
import dev.sophi.skills.SkillLock
import dev.sophi.skills.SkillPin
import dev.sophi.skills.skillContentHash
import dev.sophi.mcp.McpClientManager
import dev.sophi.mcp.McpConnector
import dev.sophi.mcp.McpSession
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun skillsDir(): Path = Files.createTempDirectory("doctor-skills").also {
    it.resolve("greet.md").writeText("---\nname: greet\ndescription: says hi\n---\nSay hi.\n")
}

private fun server(name: String) = McpServerConfig(name, McpTransport.STDIO, command = listOf(name))

class DoctorTest : FunSpec({
    test("a pinned skill whose files changed is a failing check carrying the refused line") {
        val dir = skillsDir()
        SkillLock.write(dir, mapOf("greet" to SkillPin(source = "github.com/x/y", sha256 = "0".repeat(64))))
        val checks = checkSkillPins(dir, Path.of("/nonexistent-project-skills"))
        checks shouldHaveSize 1
        checks[0].ok shouldBe false
        checks[0].detail shouldContain "Skill 'greet'"
        checks[0].fix!! shouldContain "--update"
    }

    test("an intact pin is ok") {
        val dir = skillsDir()
        SkillLock.write(dir, mapOf("greet" to SkillPin(source = "github.com/x/y", sha256 = skillContentHash(dir, "greet"))))
        checkSkillPins(dir, Path.of("/nonexistent-project-skills")).single().ok shouldBe true
    }

    test("MCP: ok shows the tool count, a throw and a hang past the timeout fail with fix lines") {
        val checks = checkMcpServers(
            listOf(server("good"), server("broken"), server("slow")),
            Path.of("/tmp/mcp.json"),
            connect = { c ->
                when (c.name) {
                    "good" -> 3
                    "broken" -> throw IllegalStateException("command not found: broken")
                    else -> { delay(10.seconds); 1 }
                }
            },
            timeout = 200.milliseconds,
        )
        checks.map { it.ok } shouldBe listOf(true, false, false)
        checks[0].detail shouldContain "3 tools"
        checks[1].detail shouldContain "command not found"
        checks[1].fix!! shouldContain "/tmp/mcp.json"
        checks[2].detail shouldContain "timed out"
        checks[2].fix!! shouldContain "/tmp/mcp.json"
    }

    test("MCP: disabled servers are skipped, and no servers is ok") {
        checkMcpServers(listOf(server("off").copy(enabled = false)), Path.of("mcp.json"), { 1 }, 1.seconds)
            .single().ok shouldBe true
    }

    test("checkCommand: a missing required command fails with the hint as fix; not required is ok with the hint") {
        val required = checkCommand("docker", listOf("no-such-command-xyz", "info"), 2.seconds, required = true, hint = "start Docker Desktop")
        required.ok shouldBe false
        required.fix shouldBe "start Docker Desktop"
        val info = checkCommand("claude", listOf("no-such-command-xyz", "--version"), 2.seconds, required = false, hint = "invoke_claude_code needs the claude CLI")
        info.ok shouldBe true
        info.detail shouldContain "invoke_claude_code needs the claude CLI"
    }

    test("checkCommand: a command that exits 0 is ok") {
        checkCommand("true", listOf("true"), 2.seconds, required = true, hint = "x").ok shouldBe true
    }

    test("mcpToolCounter closes the session even when listing tools fails after the connect") {
        var closed = false
        val session = object : McpSession {
            override suspend fun listTools() = error("list failed")
            override suspend fun callTool(name: String, argumentsJson: String) = ""
            override suspend fun close() { closed = true }
        }
        val counter = mcpToolCounter(McpClientManager(stdioConnector = object : McpConnector {
            override suspend fun connect(config: McpServerConfig) = session
        }))
        shouldThrowAny { counter(server("broken")) }
        closed shouldBe true
    }
})
