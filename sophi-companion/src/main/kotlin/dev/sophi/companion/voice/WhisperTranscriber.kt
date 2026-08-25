package dev.sophi.companion.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

interface WhisperTranscriber {
    suspend fun transcribe(wavFile: Path): Result<String>
}

/**
 * Shells out to a locally-installed whisper.cpp binary per call. Assumes a build that supports
 * `-nt` (no-timestamps) — without it, the transcript will include timestamp prefixes per line,
 * a known limitation rather than something this parses around.
 */
class ProcessWhisperTranscriber(private val config: VoiceConfig) : WhisperTranscriber {
    override suspend fun transcribe(wavFile: Path): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(
                config.whisperBinaryPath, "-m", config.whisperModelPath, "-f", wavFile.toString(), "-nt"
            ).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "whisper.cpp exited with code $exitCode (binary: ${config.whisperBinaryPath}): $stderr"
            }
            stdout.trim()
        }
    }
}
