package dev.sophi.companion.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

private class FakeAudioRecorder(private val wavFile: Path) : AudioRecorder {
    var startCalls = 0
    override fun start() { startCalls++ }
    override fun stop(): Path = wavFile
}

private class FakeWhisperTranscriber(private val result: Result<String>) : WhisperTranscriber {
    var calls = mutableListOf<Path>()
    override suspend fun transcribe(wavFile: Path): Result<String> {
        calls.add(wavFile)
        return result
    }
}

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

class VoiceControllerTest : FunSpec({
    test("a successful turn transcribes, sends, splits streamed tokens into sentences, and synthesizes them in order") {
        val wavFile = Files.createTempFile("voice-controller-test", ".wav")
        val recorder = FakeAudioRecorder(wavFile)
        val transcriber = FakeWhisperTranscriber(Result.success("hello there"))
        val synthesizer = FakePiperSynthesizer()
        val player = FakeAudioPlayer()
        var capturedOnToken: ((String) -> Unit)? = null
        var capturedOnTurnEnd: (() -> Unit)? = null
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, _, onToken, onTurnEnd ->
                capturedOnToken = onToken
                capturedOnTurnEnd = onTurnEnd
            },
            recorder = recorder,
            transcriber = transcriber,
            synthesizer = synthesizer,
            player = player
        )

        controller.onPttPress()
        controller.onPttRelease()
        runBlocking { withTimeout(2000) { waitUntil { capturedOnToken != null && capturedOnTurnEnd != null } } }

        transcriber.calls shouldBe listOf(wavFile)
        recorder.startCalls shouldBe 1

        capturedOnToken!!("Hi. ")
        capturedOnToken!!("Bye. ")
        capturedOnTurnEnd!!()

        runBlocking { withTimeout(2000) { waitUntil { player.enqueued.size == 2 } } }
        synthesizer.calls shouldBe listOf("Hi.", "Bye.")
    }

    test("flush()'s remainder at turn end is synthesized too") {
        val wavFile = Files.createTempFile("voice-controller-test", ".wav")
        val transcriber = FakeWhisperTranscriber(Result.success("hi"))
        val synthesizer = FakePiperSynthesizer()
        val player = FakeAudioPlayer()
        var capturedOnToken: ((String) -> Unit)? = null
        var capturedOnTurnEnd: (() -> Unit)? = null
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, _, onToken, onTurnEnd -> capturedOnToken = onToken; capturedOnTurnEnd = onTurnEnd },
            recorder = FakeAudioRecorder(wavFile),
            transcriber = transcriber,
            synthesizer = synthesizer,
            player = player
        )

        controller.onPttPress()
        controller.onPttRelease()
        runBlocking { withTimeout(2000) { waitUntil { capturedOnToken != null && capturedOnTurnEnd != null } } }

        capturedOnToken!!("No trailing punctuation")
        capturedOnTurnEnd!!()

        runBlocking { withTimeout(2000) { waitUntil { player.enqueued.size == 1 } } }
        synthesizer.calls shouldBe listOf("No trailing punctuation")
    }

    test("a transcription failure surfaces as VoiceState.Error and never calls sendMessage") {
        var sendMessageCalls = 0
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, _, _, _ -> sendMessageCalls++ },
            recorder = FakeAudioRecorder(Files.createTempFile("voice-controller-test", ".wav")),
            transcriber = FakeWhisperTranscriber(Result.failure(RuntimeException("whisper.cpp not found"))),
            synthesizer = FakePiperSynthesizer(),
            player = FakeAudioPlayer()
        )

        controller.onPttPress()
        controller.onPttRelease()

        runBlocking { withTimeout(2000) { waitUntil { controller.state.value is VoiceState.Error } } }
        (controller.state.value as VoiceState.Error).message shouldBe "whisper.cpp not found"
        sendMessageCalls shouldBe 0
    }

    test("a synthesis failure on one sentence doesn't stop later sentences from being spoken") {
        val transcriber = FakeWhisperTranscriber(Result.success("hi"))
        val synthesizer = FakePiperSynthesizer().apply { failOn = "First." }
        val player = FakeAudioPlayer()
        var capturedOnToken: ((String) -> Unit)? = null
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, _, onToken, _ -> capturedOnToken = onToken },
            recorder = FakeAudioRecorder(Files.createTempFile("voice-controller-test", ".wav")),
            transcriber = transcriber,
            synthesizer = synthesizer,
            player = player
        )

        controller.onPttPress()
        controller.onPttRelease()
        runBlocking { withTimeout(2000) { waitUntil { capturedOnToken != null } } }

        capturedOnToken!!("First. Second. ")

        runBlocking { withTimeout(2000) { waitUntil { synthesizer.calls.size == 2 } } }
        player.enqueued.size shouldBe 1 // only "Second." made it to the player
    }

    test("a PTT press while a turn is already in flight is ignored") {
        val recorder = FakeAudioRecorder(Files.createTempFile("voice-controller-test", ".wav"))
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, _, _, _ -> },
            recorder = recorder,
            transcriber = FakeWhisperTranscriber(Result.success("hi")),
            synthesizer = FakePiperSynthesizer(),
            player = FakeAudioPlayer()
        )

        controller.onPttPress()
        controller.onPttPress() // still recording/in-flight — should be ignored

        recorder.startCalls shouldBe 1
    }

    test("a PTT press while only playback is ongoing (turn already ended) stops playback and starts a new recording") {
        val player = FakeAudioPlayer()
        val recorder = FakeAudioRecorder(Files.createTempFile("voice-controller-test", ".wav"))
        var capturedOnTurnEnd: (() -> Unit)? = null
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, _, _, onTurnEnd -> capturedOnTurnEnd = onTurnEnd },
            recorder = recorder,
            transcriber = FakeWhisperTranscriber(Result.success("hi")),
            synthesizer = FakePiperSynthesizer(),
            player = player
        )

        controller.onPttPress()
        controller.onPttRelease()
        runBlocking { withTimeout(2000) { waitUntil { capturedOnTurnEnd != null } } }
        capturedOnTurnEnd!!() // turn ends; player may still be draining its queue in production

        controller.onPttPress() // barge-in

        recorder.startCalls shouldBe 2
        // stopAll() is called unconditionally on every successful press (harmless no-op when
        // nothing's playing), so both the initial press and the barge-in press count here.
        player.stopAllCalls shouldBe 2
    }
})
