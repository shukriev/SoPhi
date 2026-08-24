package dev.sophi.companion.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

interface Downloader {
    suspend fun download(url: String, dest: Path, onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit): Result<Unit>
}

/**
 * Both Hugging Face and GitHub release-asset URLs redirect (verified directly: HTTP/2 302 on
 * both) — followRedirects is not optional here, HttpClient's default is Redirect.NEVER, which
 * would otherwise write a small HTML redirect body to disk instead of the real file.
 */
class ProcessDownloader : Downloader {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    override suspend fun download(
        url: String,
        dest: Path,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} for $url" }
            val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
            dest.parent?.let { Files.createDirectories(it) }
            response.body().use { input ->
                Files.newOutputStream(dest).use { output ->
                    val buffer = ByteArray(65536)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
    }
}

interface Extractor {
    suspend fun extractTarGz(tarFile: Path, destDir: Path): Result<Unit>
}

/**
 * The JDK has no tar support. Shelling out to the system tar (present on every macOS install,
 * the same tool sub-project A's own CI uses to *package* these tarballs) preserves the POSIX
 * executable bit and symlinks a naive byte-copying extractor would silently drop — the failure
 * mode for that would be "Permission denied" or a broken interpreter, discovered only after a
 * successful 324MB download.
 */
class ProcessExtractor : Extractor {
    override suspend fun extractTarGz(tarFile: Path, destDir: Path): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Files.createDirectories(destDir)
            val process = ProcessBuilder("tar", "xzf", tarFile.toString(), "-C", destDir.toString()).start()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) { "tar exited with code $exitCode extracting $tarFile: $stderr" }
        }
    }
}
