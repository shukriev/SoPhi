package dev.sophi.companion.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Speaks a reply's tokens sentence-by-sentence as they stream in, for any turn regardless of how
 * it was sent — typed, or via [VoiceController]. One instance per session with text-to-speech
 * enabled; [onToken]/[onTurnEnd] are called directly from
 * [dev.sophi.companion.CompanionRuntime.sendMessage]'s token loop.
 */
class SpeechOutput(
    private val synthesizer: PiperSynthesizer,
    private val player: AudioPlayer
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val splitter = SentenceSplitter()
    private val sentenceQueue = Channel<String>(Channel.UNLIMITED)
    val isSpeaking: StateFlow<Boolean> get() = player.isPlaying

    init {
        // One consumer, one sentence at a time: synthesis of sentence n+1 isn't started until
        // sentence n has been synthesized and handed to the player, so playback order is
        // guaranteed without AudioPlayer needing to reorder anything. This alone still lets
        // synthesis of n+1 overlap *playback* of n, since playback runs on AudioPlayer's own
        // queue/thread.
        scope.launch {
            for (sentence in sentenceQueue) {
                synthesizer.synthesize(sentence).onSuccess { player.enqueue(it) }
                // A synthesis failure on one sentence is skipped, not turn-fatal.
            }
        }
    }

    fun onToken(text: String) {
        splitter.onToken(text).forEach { sentenceQueue.trySend(it) }
    }

    fun onTurnEnd() {
        splitter.flush()?.let { sentenceQueue.trySend(it) }
    }

    fun stopSpeaking() = player.stopAll()
}
