package dev.sophi.companion.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Orchestrates one push-to-talk speech-to-text turn at a time on [sessionId]. [sendMessage] has
 * the same shape as `CompanionRuntime.sendMessage(sessionId, input, onTurnEnd)` — the real
 * `CompanionRuntime` is wired in by [dev.sophi.companion.CompanionRuntime.voiceController]; tests
 * pass a fake here directly. Speaking replies back is a separate concern owned by [SpeechOutput]
 * — [onBargeIn] is this controller's only hook into it, so pressing PTT again can interrupt
 * whatever's still being spoken without this class needing to own a player.
 */
class VoiceController(
    private val sessionId: String,
    private val sendMessage: (sessionId: String, text: String, onTurnEnd: () -> Unit) -> Unit,
    private val recorder: AudioRecorder,
    private val transcriber: WhisperTranscriber,
    private val onBargeIn: () -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val turnInFlight = AtomicBoolean(false)
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state

    fun onPttPress() {
        if (!turnInFlight.compareAndSet(false, true)) return
        onBargeIn() // barge-in: talking again interrupts whatever Sophi is still saying
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
                    sendMessage(sessionId, text) { turnInFlight.set(false) }
                },
                onFailure = { e ->
                    turnInFlight.set(false)
                    _state.value = VoiceState.Error(e.message ?: "transcription failed")
                }
            )
        }
    }
}
