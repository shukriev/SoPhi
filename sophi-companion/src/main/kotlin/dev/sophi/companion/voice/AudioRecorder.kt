package dev.sophi.companion.voice

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

interface AudioRecorder {
    fun start()
    fun stop(): Path
}

private val RECORDING_FORMAT = AudioFormat(16000f, 16, 1, true, false)

/**
 * Push-to-talk microphone capture via [javax.sound.sampled]. Not thread-safe across overlapping
 * start/stop pairs — [VoiceController] never calls [start] again before a matching [stop].
 */
class JavaSoundAudioRecorder : AudioRecorder {
    private var line: TargetDataLine? = null
    private var captureThread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    override fun start() {
        buffer.reset()
        val info = DataLine.Info(TargetDataLine::class.java, RECORDING_FORMAT)
        val targetLine = AudioSystem.getLine(info) as TargetDataLine
        targetLine.open(RECORDING_FORMAT)
        targetLine.start()
        line = targetLine
        captureThread = thread(name = "sophi-voice-record") {
            val chunk = ByteArray(4096)
            while (targetLine.isOpen) {
                val n = targetLine.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(buffer) { buffer.write(chunk, 0, n) }
            }
        }
    }

    override fun stop(): Path {
        val targetLine = checkNotNull(line) { "AudioRecorder.stop() called without a matching start()" }
        targetLine.stop()
        targetLine.close()
        captureThread?.join()
        line = null
        captureThread = null
        val bytes = synchronized(buffer) { buffer.toByteArray() }
        val audioStream = AudioInputStream(
            bytes.inputStream(), RECORDING_FORMAT, (bytes.size / RECORDING_FORMAT.frameSize).toLong()
        )
        val outFile = Files.createTempFile("sophi-voice-", ".wav")
        AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, outFile.toFile())
        return outFile
    }
}
