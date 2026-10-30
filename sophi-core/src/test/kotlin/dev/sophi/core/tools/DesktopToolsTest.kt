package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText

private class Calls(private val stdout: String = "") {
    val seen = mutableListOf<Pair<List<String>, String?>>()
    val run: suspend (List<String>, String?) -> Pair<Int, String> = { cmd, stdin -> seen += cmd to stdin; 0 to stdout }
}

private fun mac(calls: Calls) = SystemDesktop("Mac OS X", calls.run)
private fun tools(d: Desktop, root: java.nio.file.Path) = desktopTools(d, root).associateBy { it.name }
private fun exec(t: Tool, json: String) = runBlocking { t.execute(json) }

class DesktopToolsTest : FunSpec({
    test("macOS commands") {
        val calls = Calls("copied text")
        val t = tools(mac(calls), tempdir().toPath())
        exec(t.getValue("notify"), """{"title":"Build \"ok\"","message":"done \\ now"}""") shouldBe "Notified."
        calls.seen.last().first shouldBe listOf("osascript", "-e", "display notification \"done \\\\ now\" with title \"Build \\\"ok\\\"\"")
        exec(t.getValue("clipboard_read"), "{}") shouldBe "copied text"
        calls.seen.last().first shouldBe listOf("pbpaste")
        exec(t.getValue("clipboard_write"), """{"text":"hi"}""") shouldBe "Copied 2 chars."
        calls.seen.last() shouldBe (listOf("pbcopy") to "hi")
        exec(t.getValue("open"), """{"target":"https://x.dev"}""") shouldBe "Opened https://x.dev."
        calls.seen.last().first shouldBe listOf("open", "https://x.dev")
    }

    test("only available tools register") {
        val d = object : Desktop by mac(Calls()) { override val canClipboard = false }
        val names = desktopTools(d, tempdir().toPath()).map { it.name }
        names shouldNotContain "clipboard_read"
        names shouldNotContain "clipboard_write"
        names shouldContain "notify"
        names shouldContain "open"
    }

    test("open refuses other schemes and missing files") {
        val t = tools(mac(Calls()), tempdir().toPath()).getValue("open")
        listOf("file:///etc/passwd", "javascript:alert(1)", "nope.txt").forEach {
            exec(t, """{"target":"$it"}""") shouldStartWith "Error:"
        }
    }

    test("open refuses launchers") {
        val root = tempdir().toPath()
        root.resolve("run.command").writeText("echo hi")
        root.resolve("tool").also { it.writeText("x"); it.toFile().setExecutable(true) }
        Files.createDirectories(root.resolve("X.app"))
        root.resolve("report.html").writeText("<p>")
        val calls = Calls()
        val t = tools(mac(calls), root).getValue("open")
        listOf("run.command", "tool", "X.app").forEach { exec(t, """{"target":"$it"}""") shouldBe "Error: open won't launch programs" }
        exec(t, """{"target":"report.html"}""") shouldBe "Opened report.html."
        calls.seen.single().first shouldBe listOf("open", root.resolve("report.html").toString())
    }

    test("clipboard read is capped") {
        val t = tools(mac(Calls("x".repeat(20_000))), tempdir().toPath()).getValue("clipboard_read")
        val out = exec(t, "{}")
        out shouldEndWith "… (truncated)"
        out.length shouldBe 16_000 + "\n… (truncated)".length
    }

    test("risk levels") {
        val t = tools(mac(Calls()), tempdir().toPath())
        t.getValue("notify").riskLevel("{}") shouldBe RiskLevel.SAFE
        listOf("clipboard_read", "clipboard_write", "open").forEach { t.getValue(it).riskLevel("{}") shouldBe RiskLevel.CAUTION }
    }
})
