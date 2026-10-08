package dev.sophi.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.net.CookieManager
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val MAX_RESPONSE_CHARS = 500_000
private const val MAX_REDIRECTS = 5
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

@Serializable
private data class FetchUrlArgs(val url: String, val raw: Boolean = false)

class FetchUrlTool(
    // Redirects are followed by hand in execute(), so every hop gets the private-address check. Never switch this
    // to Redirect.NORMAL: the client would then follow a redirect to 127.0.0.1 or a metadata address unchecked.
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
) : Tool {

    override val name = "fetch_url"
    override val description = "Fetch a public http(s) URL. Follows redirects; the first line is the HTTP status and the final URL. " +
        "HTML pages come back as readable text with links as [url]; pass raw=true for the original HTML"
    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.DESTRUCTIVE
    override val parametersJson = """
        {"type":"object","properties":{"url":{"type":"string","description":"The http(s) URL to fetch"},"raw":{"type":"boolean","description":"Return the original HTML instead of readable text (default false)"}},"required":["url"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val args = json.decodeFromString<FetchUrlArgs>(argumentsJson)
        val start = URI.create(args.url)

        require(start.scheme == "http" || start.scheme == "https") {
            "Only http/https URLs are allowed: ${args.url}"
        }

        // Cookies live for this one call only: enough for sites that bounce a fresh visitor through a
        // cookie-setting redirect (nature.com loops forever without it), nothing persists or leaks between calls.
        val cookies = CookieManager()
        var uri = start
        repeat(MAX_REDIRECTS + 1) {
            refusal(uri)?.let { return@withContext it }
            val builder = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(30))
            cookies.get(uri, emptyMap()).forEach { (name, values) -> values.forEach { builder.header(name, it) } }
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            cookies.put(uri, response.headers().map())
            val location = response.headers().firstValue("Location").orElse(null)
            if (response.statusCode() !in REDIRECT_STATUSES || location == null) {
                val from = if (uri != start) " (redirected from $start)" else ""
                val contentType = response.headers().firstValue("Content-Type").orElse("")
                val body = if (!args.raw && isHtml(contentType, response.body())) htmlToText(response.body(), uri.toString())
                           else response.body()
                return@withContext "HTTP ${response.statusCode()} $uri$from\n\n${capped(body)}"
            }
            val next = uri.resolve(location)
            if (next.scheme != "http" && next.scheme != "https") {
                return@withContext "Error: redirect to a non-http(s) URL refused: $next"
            }
            uri = next
        }
        "Error: too many redirects (more than $MAX_REDIRECTS) starting at $start"
    }

    /** Null when [uri] may be fetched, else the error to return. Runs on every redirect hop. */
    private fun refusal(uri: URI): String? {
        val host = uri.host ?: return "Error: URL has no host: $uri"
        val address = runCatching { InetAddress.getByName(host) }.getOrElse {
            return "Error: could not resolve host: $host"
        }
        // Known residual risk: this check happens once per host resolution; HttpClient re-resolves DNS
        // independently at connect time, so a DNS-rebinding attack could theoretically bypass this guard.
        // Accepted as low-risk for a dev-tool agent; revisit if exposed to less-trusted callers.
        if (address.isLoopbackAddress || address.isAnyLocalAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress
        ) {
            return "Error: refusing to fetch a private/internal address: $host"
        }
        return null
    }

    private fun isHtml(contentType: String, body: String): Boolean =
        "html" in contentType.lowercase() ||
            (contentType.isEmpty() && body.trimStart().take(15).lowercase().let { it.startsWith("<!doctype html") || it.startsWith("<html") })

    /**
     * Raw HTML is mostly markup: an arXiv listing is ~80k chars of it. Keep the title, the text with its
     * line structure, and each link's absolute target, so the model can read and follow the page cheaply.
     */
    private fun htmlToText(html: String, baseUri: String): String {
        val doc = Jsoup.parse(html, baseUri)
        doc.select("script, style, noscript, svg, template, iframe").remove()
        doc.select("a[href]").forEach { a ->
            val href = a.absUrl("href")
            if (href.startsWith("http") && a.text().isNotBlank()) a.appendText(" [$href]")
        }
        doc.select("br, p, div, li, tr, h1, h2, h3, h4, h5, h6, ul, ol, table, section, article, header, footer, nav, dt, dd, pre, blockquote")
            .forEach { it.prependText("\n") }
        val text = doc.body().wholeText().lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        val title = doc.title().trim()
        return (if (title.isNotEmpty()) "Title: $title\n\n" else "") + text
    }

    private fun capped(body: String): String =
        if (body.length > MAX_RESPONSE_CHARS) body.take(MAX_RESPONSE_CHARS) + "\n... response truncated" else body
}
