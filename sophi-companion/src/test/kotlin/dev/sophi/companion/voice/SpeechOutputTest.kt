package dev.sophi.companion.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private class FakePiperSynthesizer : PiperSynthesizer {
    val calls = mutableListOf<String>()
    var failOn: String? = null
    override suspend fun synthesize(text: String): Result<ByteArray> {
        calls.add(text)
        return if (text == failOn) Result.failure(RuntimeException("synth failed"))
        else Result.success(byteArrayOf(1, 2, 3))
    }
}

private class FakeAudioPlayer : AudioPlayer {
    val enqueued = mutableListOf<ByteArray>()
    var stopAllCalls = 0
    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    override fun enqueue(audio: ByteArray) { enqueued.add(audio) }
    override fun stopAll() { stopAllCalls++ }
}

private suspend fun waitUntil(timeoutMs: Long = 2000, poll: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (poll()) return
        delay(10)
    }
    error("waitUntil timed out after ${timeoutMs}ms")
}

class SpeechOutputTest : FunSpec({
    test("onToken splits streamed text into sentences and synthesizes them in order") {
        val synthesizer = FakePiperSynthesizer()
        val player = FakeAudioPlayer()
        val speech = SpeechOutput(synthesizer, player)

        speech.onToken("Hi. ")
        speech.onToken("Bye. ")

        runBlocking { withTimeout(2000) { waitUntil { player.enqueued.size == 2 } } }
        synthesizer.calls shouldBe listOf("Hi.", "Bye.")
    }

    test("onTurnEnd synthesizes flush()'s remainder") {
        val synthesizer = FakePiperSynthesizer()
        val player = FakeAudioPlayer()
        val speech = SpeechOutput(synthesizer, player)

        speech.onToken("No trailing punctuation")
        speech.onTurnEnd()

        runBlocking { withTimeout(2000) { waitUntil { player.enqueued.size == 1 } } }
        synthesizer.calls shouldBe listOf("No trailing punctuation")
    }

    test("a synthesis failure on one sentence doesn't stop later sentences from being spoken") {
        val synthesizer = FakePiperSynthesizer().apply { failOn = "First." }
        val player = FakeAudioPlayer()
        val speech = SpeechOutput(synthesizer, player)

        speech.onToken("First. Second. ")

        runBlocking { withTimeout(2000) { waitUntil { synthesizer.calls.size == 2 } } }
        player.enqueued.size shouldBe 1 // only "Second." made it to the player
    }

    test("stopSpeaking delegates to the player") {
        val player = FakeAudioPlayer()
        val speech = SpeechOutput(FakePiperSynthesizer(), player)

        speech.stopSpeaking()

        player.stopAllCalls shouldBe 1
    }
})
