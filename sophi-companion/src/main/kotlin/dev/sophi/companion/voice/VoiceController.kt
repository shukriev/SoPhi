package dev.sophi.companion.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Recording : VoiceState
    data object Transcribing : VoiceState
    data class Error(val message: String) : VoiceState
}

/**
 * Orchestrates one push-to-talk voice turn at a time on [sessionId]. [sendMessage] has the same
 * shape as `CompanionRuntime.sendMessage(sessionId, input, onSpeechToken, onSpeechTurnEnd)` — the
 * real `CompanionRuntime` is wired in by [dev.sophi.companion.CompanionRuntime.voiceController];
 * tests pass a fake here directly.
 */
class VoiceController(
    private val sessionId: String,
    private val sendMessage: (sessionId: String, text: String, onToken: (String) -> Unit, onTurnEnd: () -> Unit) -> Unit,
    private val recorder: AudioRecorder,
    private val transcriber: WhisperTranscriber,
    private val synthesizer: PiperSynthesizer,
    private val player: AudioPlayer
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val splitter = SentenceSplitter()
    private val sentenceQueue = Channel<String>(Channel.UNLIMITED)
    private val turnInFlight = AtomicBoolean(false)
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state
    val isSpeaking: StateFlow<Boolean> get() = player.isPlaying

    init {
        // One consumer, one sentence at a time: synthesis of sentence n+1 isn't started until
        // sentence n has been synthesized and handed to the player, so playback order is
        // guaranteed without AudioPlayer needing to reorder anything. This alone still lets
        // synthesis of n+1 overlap *playback* of n, since playback runs on AudioPlayer's own
        // queue/thread.
        scope.launch {
            for (sentence in sentenceQueue) {
                synthesizer.synthesize(sentence)
                    .onSuccess { player.enqueue(it) }
                // A synthesis failure on one sentence is skipped, not turn-fatal.
            }
        }
    }

    fun onPttPress() {
        if (!turnInFlight.compareAndSet(false, true)) return
        player.stopAll() // barge-in: talking again interrupts whatever Sophi is still saying
        _state.value = VoiceState.Recording
        recorder.start()
    }

    fun onPttRelease() {
        if (_state.value != VoiceState.Recording) return
        val wavFile = recorder.stop()
        _state.value = VoiceState.Transcribing
        scope.launch {
            val result = transcriber.transcribe(wavFile)
            runCatching { wavFile.deleteIfExists() }
            result.fold(
                onSuccess = { text ->
                    if (text.isBlank()) {
                        turnInFlight.set(false)
                        _state.value = VoiceState.Idle
                        return@fold
                    }
                    _state.value = VoiceState.Idle
                    sendMessage(sessionId, text, ::onToken, ::onTurnEnd)
                },
                onFailure = { e ->
                    turnInFlight.set(false)
                    _state.value = VoiceState.Error(e.message ?: "transcription failed")
                }
            )
        }
    }

    fun stopSpeaking() = player.stopAll()

    private fun onToken(text: String) {
        splitter.onToken(text).forEach { sentenceQueue.trySend(it) }
    }

    private fun onTurnEnd() {
        splitter.flush()?.let { sentenceQueue.trySend(it) }
        turnInFlight.set(false)
    }
}
