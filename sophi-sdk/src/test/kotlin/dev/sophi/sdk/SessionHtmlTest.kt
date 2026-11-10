package dev.sophi.sdk

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.FileSessionManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path

class SessionHtmlTest : FunSpec({
    val token = "ghp_" + "a".repeat(36)

    fun session() = AgentSession("s1", title = "Demo <b>title</b>").apply {
        append(EntryRole.SYSTEM, "system prompt stays out")
        append(EntryRole.USER, "use token $token please")
        append(EntryRole.ASSISTANT, "", mapOf("toolCalls" to """[{"id":"c1","name":"read_file","argumentsJson":"{\"path\":\"a.txt\",\"api_key\":\"hunter2\"}"}]"""))
        append(EntryRole.TOOL_RESULT, "<script>alert(1)</script>", mapOf("toolCallId" to "c1", "toolName" to "read_file"))
        append(EntryRole.ASSISTANT, "<think>secret plan</think>Done & dusted")
    }

    test("everything is escaped, so a script in a tool result is inert") {
        val html = renderSessionHtml(session())
        html shouldNotContain "<script>"
        html shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
        html shouldContain "Demo &lt;b&gt;title&lt;/b&gt;"
        html shouldContain "Done &amp; dusted"
    }

    test("secrets are redacted in text and tool arguments") {
        val html = renderSessionHtml(session())
        html shouldNotContain token
        html shouldNotContain "hunter2"
        html shouldContain "[REDACTED:github_token]"
    }

    test("structure: turns, tool call, collapsed result and thinking, no system prompt, no external refs") {
        val html = renderSessionHtml(session())
        html shouldStartWith "<!DOCTYPE html>"
        html shouldContain "read_file"
        html shouldContain "a.txt"
        html shouldContain "<details><summary>thinking</summary>"
        html shouldContain "secret plan"
        Regex("<details").findAll(html).count() shouldBe 2 // tool result + thinking
        html shouldNotContain "system prompt stays out"
        Regex("(?i)(src|href)=").containsMatchIn(html) shouldBe false
    }

    test("a long tool result is cut with a note") {
        val s = AgentSession("s2").apply { append(EntryRole.TOOL_RESULT, "x".repeat(50_000), mapOf("toolName" to "bash")) }
        val html = renderSessionHtml(s)
        html shouldContain "cut: 50000 chars"
        (html.length < 30_000) shouldBe true
    }

    test("export writes the file and never overwrites without force") {
        val dir = tempdir().toPath()
        val mgr = FileSessionManager(dir.resolve("sessions"))
        val s = mgr.create("t").also { it.append(EntryRole.USER, "hi"); mgr.save(it) }
        val out = dir.resolve("out.html")
        exportSessionHtml(mgr, s.id, out, force = false) shouldBe out
        Files.readString(out) shouldContain "hi"
        shouldThrow<IllegalStateException> { exportSessionHtml(mgr, s.id, out, force = false) }
        exportSessionHtml(mgr, s.id, out, force = true)
        defaultSessionHtmlPath(s.id) shouldBe Path.of("sophi-session-${s.id}.html")
    }
})
