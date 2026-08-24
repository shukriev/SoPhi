package dev.sophi.companion.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.readText

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

sealed interface InstallState {
    data object Idle : InstallState
    data object CheckingExisting : InstallState
    data class Downloading(val artifact: String, val bytesDone: Long, val bytesTotal: Long) : InstallState
    data object Verifying : InstallState
    data object Extracting : InstallState
    data object Ready : InstallState
    data class Error(val message: String) : InstallState
}

@Serializable
private data class ManifestArtifact(val file: String, val sha256: String, val sizeBytes: Long)

@Serializable
private data class VoiceToolsManifest(
    val releaseTag: String,
    val whisperCppRef: String,
    val piperTtsVersion: String,
    val artifacts: Map<String, ManifestArtifact>
)

internal data class PinnedModelArtifact(val url: String, val fileName: String, val sha256: String)

internal const val MANIFEST_URL =
    "https://github.com/shukriev/SoPhi/releases/download/voice-tools-v1/voice-tools-manifest.json"
private const val RELEASE_BASE_URL = "https://github.com/shukriev/SoPhi/releases/download/voice-tools-v1"

/** Pinned by direct verification (downloaded, hashed) — see the design spec for how. Pinned to
 *  exact commit SHAs, not `resolve/main`, since main is mutable and a re-upload would otherwise
 *  turn every future install into an unrecoverable checksum hard-fail. */
internal val MODEL_ARTIFACTS = listOf(
    PinnedModelArtifact(
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en.bin",
        fileName = "ggml-base.en.bin",
        sha256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002"
    ),
    PinnedModelArtifact(
        url = "https://huggingface.co/rhasspy/piper-voices/resolve/f5a6e9094787fd865d65cb024472f977f9c542b5/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
        fileName = "en_US-lessac-medium.onnx",
        sha256 = "5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f"
    ),
    PinnedModelArtifact(
        url = "https://huggingface.co/rhasspy/piper-voices/resolve/f5a6e9094787fd865d65cb024472f977f9c542b5/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
        fileName = "en_US-lessac-medium.onnx.json",
        sha256 = "efe19c417bed055f2d69908248c6ba650fa135bc868b0e6abb3da181dab690a0"
    )
)

/** Maps the JVM's os.arch to the manifest's arch keys, or null if unsupported. A standalone,
 *  internal (not private) function so VoiceInstallerTest can compute the same arch this runs on
 *  instead of hardcoding "arm64" — a test that assumed the CI/dev machine's architecture would
 *  silently break on an x86_64 runner. */
internal fun detectArch(): String? = when (System.getProperty("os.arch")) {
    "aarch64" -> "arm64"
    "x86_64" -> "x64"
    else -> null
}

private val manifestJson = Json { ignoreUnknownKeys = true }

class VoiceInstaller(
    private val downloader: Downloader,
    private val extractor: Extractor,
    private val scope: CoroutineScope,
    private val installDir: Path = Path.of(System.getProperty("user.home"), ".sophi", "voice")
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state
    private val installing = AtomicBoolean(false)

    private val whisperDir get() = installDir.resolve("bin/whisper")
    private val piperDir get() = installDir.resolve("bin/piper")
    private val modelsDir get() = installDir.resolve("models")

    /** Whisper's own binary path, once installed. */
    val whisperBinaryPath: Path get() = whisperDir.resolve("whisper-cli")

    /** Piper's embeddable Python interpreter path, once installed. */
    val piperPythonPath: Path get() = piperDir.resolve("python/bin/python3")

    fun modelPath(fileName: String): Path = modelsDir.resolve(fileName)

    suspend fun status(): InstallState = if (allFilesPresentAndValid()) InstallState.Ready else InstallState.Idle

    fun install() {
        if (!installing.compareAndSet(false, true)) return
        scope.launch {
            try {
                runInstall()
            } finally {
                installing.set(false)
            }
        }
    }

    private suspend fun runInstall() {
        _state.value = InstallState.CheckingExisting
        if (allFilesPresentAndValid()) {
            _state.value = InstallState.Ready
            return
        }

        val tempDir = Files.createTempDirectory("sophi-voice-install-")
        try {
            val arch = detectArch() ?: run {
                _state.value = InstallState.Error("Unsupported architecture: ${System.getProperty("os.arch")}")
                return
            }

            val manifestFile = tempDir.resolve("voice-tools-manifest.json")
            var failed = false
            downloader.download(MANIFEST_URL, manifestFile) { done, total ->
                _state.value = InstallState.Downloading("voice-tools-manifest.json", done, total)
            }.onFailure {
                _state.value = InstallState.Error("Failed to fetch manifest: ${it.message}")
                failed = true
            }
            if (failed) return

            val manifest = manifestJson.decodeFromString<VoiceToolsManifest>(manifestFile.readText())
            val whisperArtifact = manifest.artifacts["whisper-cli-macos-$arch"]
            val piperArtifact = manifest.artifacts["piper-runtime-macos-$arch"]
            if (whisperArtifact == null || piperArtifact == null) {
                _state.value = InstallState.Error("Manifest is missing an artifact for architecture $arch")
                return
            }

            data class PendingDownload(val url: String, val dest: Path, val sha256: String)
            val downloads = listOf(
                PendingDownload("$RELEASE_BASE_URL/${whisperArtifact.file}", tempDir.resolve(whisperArtifact.file), whisperArtifact.sha256),
                PendingDownload("$RELEASE_BASE_URL/${piperArtifact.file}", tempDir.resolve(piperArtifact.file), piperArtifact.sha256)
            ) + MODEL_ARTIFACTS.map { PendingDownload(it.url, tempDir.resolve(it.fileName), it.sha256) }

            for (d in downloads) {
                downloader.download(d.url, d.dest) { done, total ->
                    _state.value = InstallState.Downloading(d.dest.fileName.toString(), done, total)
                }.onFailure {
                    _state.value = InstallState.Error("Failed to download ${d.dest.fileName}: ${it.message}")
                    failed = true
                }
                if (failed) return
            }

            _state.value = InstallState.Verifying
            for (d in downloads) {
                val actual = sha256(d.dest)
                if (actual != d.sha256) {
                    _state.value = InstallState.Error(
                        "Checksum mismatch for ${d.dest.fileName}: expected ${d.sha256}, got $actual"
                    )
                    return
                }
            }

            _state.value = InstallState.Extracting
            extractor.extractTarGz(tempDir.resolve(whisperArtifact.file), whisperDir).onFailure {
                _state.value = InstallState.Error("Failed to extract whisper-cli: ${it.message}")
                failed = true
            }
            if (failed) return
            extractor.extractTarGz(tempDir.resolve(piperArtifact.file), piperDir).onFailure {
                _state.value = InstallState.Error("Failed to extract piper runtime: ${it.message}")
                failed = true
            }
            if (failed) return
            Files.createDirectories(modelsDir)
            MODEL_ARTIFACTS.forEach {
                Files.move(
                    tempDir.resolve(it.fileName), modelsDir.resolve(it.fileName),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }

            _state.value = InstallState.Ready
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /** Existence-only for the two tool binaries (their checksums only exist in the manifest, and
     *  fetching it is a network call this method must not make); existence-plus-checksum for the
     *  three model files (checksums are pinned in code, no network needed). This asymmetry is
     *  deliberate — see the design spec's status() section. */
    private fun allFilesPresentAndValid(): Boolean {
        if (!Files.isExecutable(whisperBinaryPath) || !Files.isExecutable(piperPythonPath)) return false
        return MODEL_ARTIFACTS.all { artifact ->
            val f = modelsDir.resolve(artifact.fileName)
            Files.exists(f) && sha256(f) == artifact.sha256
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
