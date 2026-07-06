package dev.sophi.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val MAX_RESPONSE_CHARS = 500_000

@Serializable
private data class FetchUrlArgs(val url: String)

class FetchUrlTool(
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
) : Tool {

    override val name = "fetch_url"
    override val description = "Fetch the text content of a public http(s) URL"
    override val parametersJson = """
        {"type":"object","properties":{"url":{"type":"string","description":"The http(s) URL to fetch"}},"required":["url"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val args = json.decodeFromString<FetchUrlArgs>(argumentsJson)
        val uri = URI.create(args.url)

        require(uri.scheme == "http" || uri.scheme == "https") {
            "Only http/https URLs are allowed: ${args.url}"
        }

        val host = uri.host ?: return@withContext "Error: URL has no host: ${args.url}"
        val address = runCatching { InetAddress.getByName(host) }.getOrElse {
            return@withContext "Error: could not resolve host: $host"
        }
        if (address.isLoopbackAddress || address.isAnyLocalAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress
        ) {
            return@withContext "Error: refusing to fetch a private/internal address: $host"
        }

        val request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(30)).build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()
        if (body.length > MAX_RESPONSE_CHARS) {
            body.take(MAX_RESPONSE_CHARS) + "\n... response truncated"
        } else {
            body
        }
    }
}
