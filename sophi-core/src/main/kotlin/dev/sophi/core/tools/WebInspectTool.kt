package dev.sophi.core.tools

import dev.sophi.core.audit.redactTokens
import dev.sophi.core.permissions.PermissionSubject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.net.http.HttpClient
import java.net.http.HttpResponse

private const val MAX_ITEMS = 100

/** Names whose values are never shown: headers, meta tags, query parameters. */
private val SECRET_NAME = Regex("(?i)token|auth|session|secret|passw|csrf|xsrf|cookie|api.?key|^key$|^sid$|^sig$|signature|^code$")

private val TOKEN_RUN = Regex("[A-Za-z0-9_+/=-]{24,}")

/**
 * A value that looks like a secret whatever it is named: a known token shape (the shared SECRET_PATTERNS), or a 24+ char
 * run mixing upper case, lower case and digits. ponytail: a heuristic, so an odd asset hash may be withheld too.
 */
private fun secretLike(value: String) = redactTokens(value) != value ||
    TOKEN_RUN.findAll(value).any { m -> m.value.any(Char::isUpperCase) && m.value.any(Char::isLowerCase) && m.value.any(Char::isDigit) }

private fun unlessSecret(value: String) = if (secretLike(value)) "[redacted]" else value

@Serializable
private data class WebInspectArgs(val url: String)

/**
 * A page's structure, observed only: one GET (the same fetch, redirect and private-address handling as `fetch_url`), parsed
 * as static HTML. Never clicks, types or runs script. Cookies, auth headers, form values, inline script, secret-named
 * meta and query values, and any header, meta or query value shaped like a secret are never shown.
 */
class WebInspectTool(httpClient: HttpClient? = null) : Tool {
    private val fetcher = httpClient?.let { FetchUrlTool(it) } ?: FetchUrlTool()

    override val name = "web_inspect"
    override val description = "Outline a public web page without acting on it: status, headers, title, meta, headings, links, " +
        "form field names (never values) and resources. Static HTML only, no script run; cookies and auth headers withheld."
    override val parametersJson = """
        {"type":"object","properties":{"url":{"type":"string","description":"The http(s) URL to inspect"}},"required":["url"]}
    """.trimIndent()
    override val deferred = true
    override val outsideText = true
    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.DESTRUCTIVE
    override fun effects(argumentsJson: String) = setOf(EffectKind.NETWORK)
    override fun permissionSubjects(argumentsJson: String): List<PermissionSubject>? = fetcher.urlSubjects(argumentsJson)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String =
        fetcher.fetch(json.decodeFromString<WebInspectArgs>(argumentsJson).url) { response, uri, from ->
            fetcher.capped("HTTP ${response.statusCode()} ${clean(uri.toString())}$from\n" + outline(response, uri.toString()))
        }

    private fun outline(response: HttpResponse<String>, base: String): String = buildString {
        val headers = response.headers().map()
        val (withheld, shown) = headers.keys.partition { SECRET_NAME.containsMatchIn(it) }
        appendLine("Headers${if (withheld.isNotEmpty()) " (${withheld.size} headers withheld)" else ""}:")
        shown.sortedBy { it.lowercase() }.forEach { appendLine("${it.lowercase()}: ${unlessSecret(headers.getValue(it).joinToString(", "))}") }

        val type = response.headers().firstValue("Content-Type").orElse("")
        if (type.isNotEmpty() && "html" !in type.lowercase()) { append("Not an HTML page ($type)."); return@buildString }

        val doc = Jsoup.parse(response.body(), base)
        appendLine("Title: ${doc.title().trim()}")
        doc.selectFirst("html[lang]")?.let { appendLine("lang: ${it.attr("lang")}") }
        doc.selectFirst("link[rel=canonical][href]")?.let { appendLine("canonical: ${clean(it.absUrl("href"))}") }

        appendLine("Meta:")
        doc.select("meta[content]").take(MAX_ITEMS).forEach { m ->
            val key = listOf("name", "property", "http-equiv").map { m.attr(it) }.firstOrNull { it.isNotEmpty() } ?: return@forEach
            appendLine("$key: ${if (SECRET_NAME.containsMatchIn(key)) "[redacted]" else unlessSecret(m.attr("content")).take(200)}")
        }

        appendLine("Headings:")
        doc.select("h1, h2, h3, h4, h5, h6").take(MAX_ITEMS).forEach { h ->
            appendLine("  ".repeat(h.tagName()[1] - '1') + "${h.tagName()} ${h.text().take(120)}")
        }

        appendLine("Links:")
        doc.select("a[href]").map { clean(it.absUrl("href")) to it.text().trim().take(80) }
            .filter { it.first.startsWith("http") }.distinctBy { it.first }.take(MAX_ITEMS)
            .forEach { (href, text) -> appendLine("$href  $text") }

        appendLine("Forms:")
        doc.select("form").take(MAX_ITEMS).forEach { f ->
            appendLine("form ${f.attr("method").ifEmpty { "get" }.uppercase()} ${clean(f.absUrl("action").ifEmpty { base })}")
            // Field names and types only: value, textarea text and selected options are never read.
            f.select("input[name], select[name], textarea[name]").forEach { field ->
                val kind = if (field.tagName() == "input") field.attr("type").ifEmpty { "text" }.lowercase() else field.tagName()
                appendLine("  $kind ${field.attr("name")}${if (field.hasAttr("required")) " required" else ""}")
            }
        }

        appendLine("Resources:")
        val resources = doc.select("script[src]").map { "script" to it.absUrl("src") } +
            doc.select("link[rel=stylesheet][href]").map { "stylesheet" to it.absUrl("href") } +
            doc.select("img[src]").map { "img" to it.absUrl("src") } +
            doc.select("iframe[src]").map { "iframe" to it.absUrl("src") }
        resources.filter { it.second.isNotEmpty() }.take(MAX_ITEMS).forEach { (kind, src) -> appendLine("$kind ${clean(src)}") }
        val scripts = doc.select("script")
        // Inline script and style bodies are where bootstrapped state and tokens live: counted, never shown.
        append("inline scripts: ${scripts.count { !it.hasAttr("src") }}, inline styles: ${doc.select("style").size}")
        if (scripts.isNotEmpty() && doc.body().text().length < 50 && doc.select("a[href], h1, h2, h3").isEmpty()) {
            append("\nNote: little text and ${scripts.size} scripts: the page likely builds itself with script; this is the static HTML only.")
        }
    }

    /** Drops userinfo and the values of secret-named or secret-shaped query parameters. */
    private fun clean(url: String): String =
        url.replace(Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/?#@]*@"), "$1")
            .replace(Regex("([?&])([^=&#]+)=([^&#]*)")) { m ->
                if (SECRET_NAME.containsMatchIn(m.groupValues[2]) || secretLike(m.groupValues[3])) "${m.groupValues[1]}${m.groupValues[2]}=[redacted]" else m.value
            }
}
