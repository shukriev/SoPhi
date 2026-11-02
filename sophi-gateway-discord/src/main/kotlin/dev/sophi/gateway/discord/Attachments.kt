package dev.sophi.gateway.discord

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

private val CDN_HOSTS = setOf("cdn.discordapp.com", "media.discordapp.net")

/** Attachments come only from Discord's own CDN, over https on its default port. */
fun allowedAttachmentUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.host in CDN_HOSTS && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
}

/**
 * [url] saved to [to], refusing a host [allowed] doesn't accept and stopping past [maxBytes] (the
 * partial file is removed). Redirects aren't followed, so the host checked is the host read.
 */
suspend fun download(
    url: String, maxBytes: Long, to: Path, http: HttpClient, allowed: (String) -> Boolean = ::allowedAttachmentUrl,
): Result<Path> = withContext(Dispatchers.IO) {
    runCatching {
        require(allowed(url)) { "not a Discord attachment" }
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            if (response.statusCode() != 200) throw IOException("Discord answered ${response.statusCode()}")
            to.parent?.let { Files.createDirectories(it) }
            Files.newOutputStream(to).use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("too large")
                    out.write(buf, 0, n)
                }
            }
        }
        to
    }.onFailure { Files.deleteIfExists(to) }
}
