package dev.sophi.sdk

import dev.sophi.core.audit.redactArguments
import dev.sophi.core.audit.redactSecrets
import dev.sophi.core.audit.redactWindowed
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val MAX_RESULT = 8_000
private val THINK = Regex("<think>([\\s\\S]*?)(?:</think>|$)")
private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private val CSS = """
body{font:15px/1.5 system-ui,sans-serif;max-width:860px;margin:2em auto;padding:0 1em;color:#222}
.turn{margin:1em 0;padding:.6em .9em;border-radius:8px;white-space:pre-wrap;overflow-wrap:anywhere}
.user{background:#eef4ff}.assistant{background:#f5f5f5}.tool{background:#fffbea;font-size:13px}
.role{font-weight:600;font-size:12px;color:#666;display:block}
pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;margin:.3em 0}summary{cursor:pointer;color:#666}
""".trimIndent()

private fun esc(s: String) = buildString(s.length) {
    for (c in s) append(when (c) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> c })
}

/** Redacted, then escaped: model output and tool results are untrusted, so nothing reaches the page unescaped. */
private fun safe(s: String) = esc(redactSecrets(s))

private fun toolCalls(json: String?): List<Pair<String, String>> = runCatching {
    lenient.decodeFromString<List<JsonObject>>(json ?: "[]").map {
        (it["name"]?.jsonPrimitive?.content ?: "?") to (it["argumentsJson"]?.jsonPrimitive?.content ?: "")
    }
}.getOrDefault(emptyList())

private fun StringBuilder.turn(cls: String, role: String, body: String) =
    append("<div class=\"turn $cls\"><span class=\"role\">").append(role).append("</span>").append(body).append("</div>\n")

/**
 * One self-contained HTML page for [session]'s current branch: no scripts, no external requests. The system prompt is
 * left out. Every text is secret-redacted and HTML-escaped; tool results and `<think>` blocks sit in a `<details>`,
 * and a tool result over [MAX_RESULT] chars is cut with a note.
 */
fun renderSessionHtml(session: AgentSession): String {
    val title = safe(session.title ?: "Sophi session ${session.id}")
    val sb = StringBuilder("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>$title</title>\n")
        .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">\n")
        .append("<style>").append(CSS).append("</style></head><body>\n<h1>").append(title).append("</h1>\n")
    for (e in session.branch()) {
        val time = esc(Instant.ofEpochMilli(e.timestamp).toString())
        when (e.role) {
            EntryRole.SYSTEM -> Unit
            EntryRole.USER -> sb.turn("user", "you · $time", safe(e.content))
            EntryRole.ASSISTANT -> {
                val body = StringBuilder()
                THINK.findAll(e.content).forEach {
                    body.append("<details><summary>thinking</summary>").append(safe(it.groupValues[1].trim())).append("</details>")
                }
                body.append(safe(THINK.replace(e.content, "").trim()))
                toolCalls(e.metadata["toolCalls"]).forEach { (name, args) ->
                    body.append("<pre>→ ").append(esc(redactSecrets(name))).append(' ')
                        .append(esc(redactArguments(args, window = MAX_RESULT))).append("</pre>")
                }
                if (body.isNotBlank()) sb.turn("assistant", "sophi · $time", body.toString())
            }
            EntryRole.TOOL_RESULT -> {
                val cut = if (e.content.length > MAX_RESULT) " (cut: ${e.content.length} chars, first $MAX_RESULT shown)" else ""
                sb.turn("tool", "tool result · ${safe(e.metadata["toolName"] ?: "")}",
                    "<details><summary>${e.content.length} chars$cut</summary><pre>" +
                        esc(redactWindowed(e.content, MAX_RESULT)) + "</pre></details>")
            }
        }
    }
    return sb.append("</body></html>\n").toString()
}

fun defaultSessionHtmlPath(sessionId: String): Path = Path.of("sophi-session-$sessionId.html")

/** Writes [sessionId] as HTML to [out] (default `./sophi-session-<id>.html`); refuses to overwrite unless [force]. */
fun exportSessionHtml(sessions: SessionManager, sessionId: String, out: Path? = null, force: Boolean = false): Path {
    val target = out ?: defaultSessionHtmlPath(sessionId)
    check(force || !Files.exists(target)) { "$target already exists; use --force to overwrite" }
    Files.writeString(target, renderSessionHtml(sessions.load(sessionId)))
    return target
}
