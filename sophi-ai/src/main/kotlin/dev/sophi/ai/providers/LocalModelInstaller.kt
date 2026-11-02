package dev.sophi.ai.providers

import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A download pinned to an exact revision; [sha256] and [sizeBytes] are checked after download. */
data class PinnedFile(val url: String, val fileName: String, val sha256: String, val sizeBytes: Long)

const val LLAMA_CPP_TAG = "b11717"

private fun engine(platform: String, sha256: String, sizeBytes: Long) = PinnedFile(
    "https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_CPP_TAG/llama-$LLAMA_CPP_TAG-bin-$platform.tar.gz",
    "llama-$LLAMA_CPP_TAG-bin-$platform.tar.gz", sha256, sizeBytes,
)

/** llama.cpp release archives (CPU builds on Linux, Metal on macOS), hashes from the release's asset digests. */
private val ENGINES = mapOf(
    "macos-arm64" to engine("macos-arm64", "c101d5efef233ce43dd9bee93179ec180e409373d94efd2769e75ba9452eef19", 12_292_556L),
    "ubuntu-x64" to engine("ubuntu-x64", "1fce11be93423044e073a9a967b99998569c4bd343220f60ef98b080385d09cb", 17_969_007L),
    "ubuntu-arm64" to engine("ubuntu-arm64", "f829d4cac76e14e85fc1a2bcb965f424339492b3b6ecb3c4fc4c2963a746ec1f", 14_020_970L),
)

/** The reference model the playbook and the companion run on: Qwen3-8B Q4_K_M, pinned to a commit. */
val REFERENCE_MODEL = PinnedFile(
    "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/7c41481f57cb95916b40956ab2f0b139b296d974/Qwen3-8B-Q4_K_M.gguf",
    "Qwen3-8B-Q4_K_M.gguf",
    "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
    5_027_783_488L,
)

/** The llama.cpp archive for this OS and JVM arch (`os.name`, `os.arch`), or null when there is none. */
fun llamaCppEngineFor(osName: String = System.getProperty("os.name"), osArch: String = System.getProperty("os.arch")): PinnedFile? {
    val arch = when (osArch) { "aarch64", "arm64" -> "arm64"; "amd64", "x86_64" -> "x64"; else -> return null }
    val os = when {
        osName.startsWith("Mac") -> "macos"
        osName.startsWith("Linux") -> "ubuntu"
        else -> return null
    }
    // ponytail: no Intel Mac build offered; an 8B model on its CPU is too slow to be the default.
    return ENGINES["$os-$arch"]
}

fun unsupportedPlatformMessage(osName: String = System.getProperty("os.name"), osArch: String = System.getProperty("os.arch")): String = when {
    osName.startsWith("Windows") -> "Windows isn't supported yet by `sophi setup local`. Install Ollama or LM Studio instead."
    osName.startsWith("Mac") -> "Running models locally on a Mac needs Apple Silicon. Install Ollama or LM Studio instead."
    else -> "No llama.cpp build for $osName/$osArch. Install Ollama or LM Studio instead."
}

/**
 * Downloads the llama.cpp [engine] and a GGUF [model] into [installDir]. Downloads land in `partial/`
 * and survive failures, so the next run resumes them with a Range request; a checksum mismatch
 * deletes the partial file. [download] is `(url, dest, onProgress(done, total))` and must append to
 * an existing [dest] when it can resume.
 */
class LocalModelInstaller(
    private val engine: PinnedFile,
    val model: PinnedFile = REFERENCE_MODEL,
    val installDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "local-model"),
    private val download: (String, Path, (Long, Long) -> Unit) -> Unit = ::httpDownload,
    private val onProgress: (label: String, done: Long, total: Long) -> Unit = { _, _, _ -> },
) {
    private val engineDir get() = installDir.resolve("engine")
    private val partialDir get() = installDir.resolve("partial")
    val modelFile: Path get() = installDir.resolve("models").resolve(model.fileName)

    /** The llama-server the last engine install recorded; a path that doesn't exist before then. */
    val serverBinary: Path
        get() {
            val current = engineDir.resolve("current")
            return if (Files.isRegularFile(current)) Path.of(current.readText().trim()) else engineDir.resolve("llama-server")
        }

    /** Sizes only: hashing 5 GB on every check would stall; [install] verifies the hash. */
    fun isInstalled(): Boolean = engineInstalled() && modelInstalled()

    private fun engineInstalled() = Files.isExecutable(serverBinary) && serverBinary.startsWith(engineDir.resolve(LLAMA_CPP_TAG))
    private fun modelInstalled() = Files.isRegularFile(modelFile) && Files.size(modelFile) == model.sizeBytes

    /** Downloads whatever is missing. Throws [IOException] with a readable message on failure. */
    fun install() {
        if (!engineInstalled()) installEngine(fetchVerified(engine))
        if (!modelInstalled()) {
            val partial = fetchVerified(model)
            Files.createDirectories(modelFile.parent)
            Files.move(partial, modelFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** llama-server on the port `local` discovery probes for llama.cpp, so plain `sophi` finds it. */
    fun serverCommand(): List<String> = listOf(
        serverBinary.toString(), "-m", modelFile.toString(),
        "--host", "127.0.0.1", "--port", LocalServerKind.LLAMA_CPP.defaultPort.toString(),
        "--alias", "qwen3-8b", "-c", "32768", "-ngl", "999", "--jinja", "-fa", "on",
    )

    private fun fetchVerified(file: PinnedFile): Path {
        val dest = partialDir.resolve(file.fileName)
        // Already full size: a crash between download and check, so only check it.
        if (!(Files.isRegularFile(dest) && Files.size(dest) == file.sizeBytes)) {
            try {
                download(file.url, dest) { done, total -> onProgress(file.fileName, done, total) }
            } catch (e: Exception) {
                throw IOException("Couldn't download ${file.fileName}: ${e.message}", e)
            }
        }
        val actual = sha256(dest)
        if (actual != file.sha256) {
            Files.deleteIfExists(dest)
            throw IOException("Checksum mismatch for ${file.fileName}: expected ${file.sha256}, got $actual")
        }
        return dest
    }

    private fun installEngine(tarball: Path) {
        val dir = engineDir.resolve(LLAMA_CPP_TAG)
        dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        // System tar keeps the executable bit and symlinks (the Linux build's .so links).
        val tar = ProcessBuilder("tar", "xzf", tarball.toString(), "-C", dir.toString()).redirectErrorStream(true).start()
        val output = tar.inputStream.bufferedReader().readText()
        if (!tar.waitFor(10, TimeUnit.MINUTES) || tar.exitValue() != 0) throw IOException("Failed to extract llama.cpp: $output")
        val binary = Files.walk(dir).use { paths ->
            paths.filter { it.fileName.toString() == "llama-server" && Files.isRegularFile(it) }.findFirst().orElse(null)
        } ?: throw IOException("llama-server not found in the llama.cpp archive")
        engineDir.resolve("current").writeText(binary.toAbsolutePath().toString())
        Files.deleteIfExists(tarball)
    }
}

private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
    return HexFormat.of().formatHex(digest.digest())
}

/** Resumes a partial [dest] with a Range request; a server that ignores it (200) restarts the file.
 *  Hugging Face and GitHub assets redirect, so redirects must be followed. */
fun httpDownload(url: String, dest: Path, onProgress: (Long, Long) -> Unit) {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val existing = if (Files.exists(dest)) Files.size(dest) else 0L
    val request = HttpRequest.newBuilder(URI.create(url)).GET().apply { if (existing > 0) header("Range", "bytes=$existing-") }.build()
    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    // 416: the partial can't be resumed; fetch it whole.
    if (response.statusCode() == 416) {
        response.body().close()
        response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    }
    if (response.statusCode() !in 200..299) {
        response.body().close()
        throw IOException("HTTP ${response.statusCode()} for $url")
    }
    val resumed = response.statusCode() == 206
    var done = if (resumed) existing else 0L
    val length = response.headers().firstValueAsLong("content-length").orElse(-1L)
    val total = if (length >= 0) done + length else -1L
    Files.createDirectories(dest.parent)
    val options = if (resumed) arrayOf(StandardOpenOption.APPEND)
    else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    response.body().use { input ->
        Files.newOutputStream(dest, *options).use { output ->
            val buffer = ByteArray(65536)
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
