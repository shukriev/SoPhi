package dev.sophi.companion.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PiperSynthesizer {
    suspend fun synthesize(text: String): Result<ByteArray>
}

/** Raw PCM format piper's `--output-raw` mode writes to stdout: 22.05kHz, mono, 16-bit signed. */
object PiperAudioFormat {
    const val SAMPLE_RATE = 22050f
    const val BITS = 16
    const val CHANNELS = 1
}

/**
 * Shells out to a locally-installed piper binary per sentence, writing [text] to stdin and
 * reading raw PCM audio ([PiperAudioFormat]) from stdout.
 *
 * ponytail: spawns a fresh piper process (reloading the voice model) per call. Fine for
 * occasional sentences; if replies with many short sentences make playback choppy in practice,
 * the fix is a long-lived piper process fed one line per sentence on stdin (piper supports this
 * natively) instead of one-shot invocations.
 */
class ProcessPiperSynthesizer(private val config: VoiceConfig) : PiperSynthesizer {
    override suspend fun synthesize(text: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(
                config.piperBinaryPath, "--model", config.piperVoicePath, "--output-raw"
            ).start()
            process.outputStream.use { it.write((text + "\n").toByteArray()) }
            val audio = process.inputStream.readBytes()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "piper exited with code $exitCode (binary: ${config.piperBinaryPath}): $stderr"
            }
            audio
        }
    }
}
