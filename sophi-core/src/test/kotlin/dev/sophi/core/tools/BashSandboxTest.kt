package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

private fun BashTool.run(command: String) = runBlocking {
    execute(kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive(command)) }.toString())
}

class BashSandboxTest : FunSpec({
    test("off runs the command unwrapped") {
        BashSandbox(createTempDirectory("ws")).wrap(listOf("sh", "-c", "x")) shouldBe listOf("sh", "-c", "x")
    }

    context("on macOS").config(enabled = SeatbeltSandbox.available) {
        test("a switch applies from the next command") {
            val ws = createTempDirectory("ws")
            val elsewhere = createTempDirectory("elsewhere")
            val sandbox = BashSandbox(ws, SeatbeltSandbox(listOf(ws))) // temp dirs are writable by default
            val tool = BashTool(ws, sandbox)
            tool.run("touch '$elsewhere/a'") shouldNotContain "Operation not permitted"
            sandbox.set(SandboxMode.SEATBELT) shouldBe null
            tool.run("touch '$elsewhere/b'") shouldContain "Operation not permitted"
            sandbox.set(SandboxMode.OFF) shouldBe null
            tool.run("touch '$elsewhere/c'") shouldNotContain "Operation not permitted"
            listOf("a", "b", "c").map { elsewhere.resolve(it).exists() } shouldBe listOf(true, false, true)
        }

        test("a workspace path with quotes, spaces and parentheses works under seatbelt") {
            val parent = createTempDirectory("ws")
            val ws = parent.resolve("it's a \"(dir)\"").createDirectories()
            val sibling = parent.resolve("sibling").createDirectories()
            val tool = BashTool(ws, BashSandbox(ws, SeatbeltSandbox(listOf(ws))).apply { set(SandboxMode.SEATBELT) shouldBe null })
            tool.run("touch inside && echo made") shouldContain "made"
            tool.run("touch '$sibling/x'") shouldContain "Operation not permitted"
            sibling.resolve("x").exists() shouldBe false
        }
    }
})
