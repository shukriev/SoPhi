package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

private fun BashTool.run(command: String) = runBlocking {
    execute(kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive(command)) }.toString())
}

class SeatbeltSandboxTest : FunSpec({
    test("by default commands may write to the workspace, temp and tool caches, not the rest of home") {
        val ws = createTempDirectory("ws")
        val home = Path.of("/Users/someone")
        val writable = SeatbeltSandbox.forWorkspace(ws, home).writable
        writable shouldContain ws.toRealPath()
        writable shouldContain Path.of("/private/tmp")
        writable shouldContain home.resolve("Library/Caches")
        writable shouldContain home.resolve(".gradle/caches")
        writable shouldNotContain home
        writable shouldNotContain home.resolve(".gradle")
    }

    context("on macOS").config(enabled = SeatbeltSandbox.available) {
        test("a write outside the writable places fails; inside it works; reading anywhere works") {
            val ws = createTempDirectory("ws")
            val elsewhere = createTempDirectory("elsewhere")
            val tool = BashTool(ws, BashSandbox(ws, SeatbeltSandbox(listOf(ws))).apply { set(SandboxMode.SEATBELT) })
            tool.run("touch '$elsewhere/x'") shouldContain "Operation not permitted"
            elsewhere.resolve("x").exists() shouldBe false
            tool.run("touch inside && echo made")  shouldContain "made"
            ws.resolve("inside").exists() shouldBe true
            tool.run("cat /etc/hosts") shouldContain "localhost"
        }

        test("a path SoPhi builds at run time in home can't be written") {
            val ws = createTempDirectory("ws")
            val name = ".sophi-seatbelt-test-${System.nanoTime()}"
            val target = Path.of(System.getProperty("user.home"), name)
            try {
                BashTool(ws, BashSandbox(ws).apply { set(SandboxMode.SEATBELT) }).run("touch \"\$HOME/$name\"") shouldContain "Operation not permitted"
                target.exists() shouldBe false
            } finally { target.deleteIfExists() }
        }

        test("a workspace path with quotes, spaces and parentheses can't break out of the profile") {
            val ws = createTempDirectory("ws").resolve("a \" ) (allow file-write* (subpath \"/\") ;b\\").createDirectories()
            val elsewhere = createTempDirectory("elsewhere")
            val tool = BashTool(ws, BashSandbox(ws, SeatbeltSandbox(listOf(ws))).apply { set(SandboxMode.SEATBELT) })
            tool.run("touch inside && echo made") shouldContain "made"
            tool.run("touch '$elsewhere/x'") shouldContain "Operation not permitted"
            elsewhere.resolve("x").exists() shouldBe false
        }
        test("acceptance: ~/Desktop can't be written, the workspace can, /etc/hosts can be read") {
            val ws = createTempDirectory("ws")
            val name = "sophi-x-${System.nanoTime()}"
            val desktop = Path.of(System.getProperty("user.home"), "Desktop", name)
            val tool = BashTool(ws, BashSandbox(ws).apply { set(SandboxMode.SEATBELT) shouldBe null })
            try {
                tool.run("touch \"\$HOME/Desktop/$name\"") shouldContain "Operation not permitted"
                desktop.exists() shouldBe false
            } finally { desktop.deleteIfExists() }
            tool.run("echo hi > made && cat made") shouldContain "hi"
            tool.run("cat /etc/hosts") shouldContain "localhost"
        }

        test("with the network off, curl fails") {
            val ws = createTempDirectory("ws")
            val tool = BashTool(ws, BashSandbox(ws).apply { set(SandboxMode.SEATBELT, network = false) shouldBe null })
            val out = tool.run("curl -sS -m 5 https://example.com")
            out shouldContain "Command exited with code"
            out.contains("<html") shouldBe false
        }
    }
})
