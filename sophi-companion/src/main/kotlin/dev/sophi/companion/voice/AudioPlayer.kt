package dev.sophi.companion.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

interface AudioPlayer {
    val isPlaying: StateFlow<Boolean>
    fun enqueue(audio: ByteArray)
    fun stopAll()
}

private val PLAYBACK_FORMAT =
    AudioFormat(PiperAudioFormat.SAMPLE_RATE, PiperAudioFormat.BITS, PiperAudioFormat.CHANNELS, true, false)

/** Sequential playback queue for synthesized speech. One instance per active voice session. */
class JavaSoundAudioPlayer : AudioPlayer {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying
    @Volatile private var currentLine: SourceDataLine? = null
    // ponytail: stopAll() draining the channel races with this consumer loop also reading from
    // it — worst case one already-dequeued clip plays anyway after a stop. Acceptable for a
    // "barge in" gesture (annoying for a fraction of a second, not incorrect); a precise version
    // would need a generation counter passed through the channel instead of a shared flag.
    @Volatile private var stopRequested = false

    init {
        scope.launch {
            for (audio in queue) {
                if (stopRequested) {
                    stopRequested = false
                    continue
                }
                _isPlaying.value = true
                playBlocking(audio)
                _isPlaying.value = false
            }
        }
    }

    override fun enqueue(audio: ByteArray) {
        queue.trySend(audio)
    }

    override fun stopAll() {
        stopRequested = true
        while (queue.tryReceive().isSuccess) { /* drain anything not yet picked up */ }
        currentLine?.let { runCatching { it.stop(); it.flush() } }
    }

    private fun playBlocking(audio: ByteArray) {
        val line = AudioSystem.getSourceDataLine(PLAYBACK_FORMAT)
        line.open(PLAYBACK_FORMAT)
        line.start()
        currentLine = line
        line.write(audio, 0, audio.size)
        line.drain()
        line.close()
        currentLine = null
    }
}
