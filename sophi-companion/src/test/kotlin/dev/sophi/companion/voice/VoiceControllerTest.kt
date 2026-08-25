package dev.sophi.companion.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
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

private suspend fun waitUntil(timeoutMs: Long = 2000, poll: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (poll()) return
        delay(10)
    }
    error("waitUntil timed out after ${timeoutMs}ms")
}

class VoiceControllerTest : FunSpec({
    test("a successful turn transcribes and sends, with an onTurnEnd callback that releases the guard") {
        val wavFile = Files.createTempFile("voice-controller-test", ".wav")
        val recorder = FakeAudioRecorder(wavFile)
        val transcriber = FakeWhisperTranscriber(Result.success("hello there"))
        val sent = mutableListOf<String>()
        var capturedOnTurnEnd: (() -> Unit)? = null
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, text, onTurnEnd -> sent.add(text); capturedOnTurnEnd = onTurnEnd },
            recorder = recorder,
            transcriber = transcriber
        )

        controller.onPttPress()
        controller.onPttRelease()
        runBlocking { withTimeout(2000) { waitUntil { sent.isNotEmpty() } } }

        transcriber.calls shouldBe listOf(wavFile)
        recorder.startCalls shouldBe 1
        sent shouldBe listOf("hello there")
        (capturedOnTurnEnd != null) shouldBe true
    }

    test("a blank transcript sends nothing and returns to Idle") {
        val transcriber = FakeWhisperTranscriber(Result.success("   "))
        var sendMessageCalls = 0
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, _, _ -> sendMessageCalls++ },
            recorder = FakeAudioRecorder(Files.createTempFile("voice-controller-test", ".wav")),
            transcriber = transcriber
        )

        controller.onPttPress()
        controller.onPttRelease()

        runBlocking { withTimeout(2000) { waitUntil { controller.state.value == VoiceState.Idle } } }
        sendMessageCalls shouldBe 0
    }

    test("a transcription failure surfaces as VoiceState.Error and never calls sendMessage") {
        var sendMessageCalls = 0
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, _, _ -> sendMessageCalls++ },
            recorder = FakeAudioRecorder(Files.createTempFile("voice-controller-test", ".wav")),
            transcriber = FakeWhisperTranscriber(Result.failure(RuntimeException("whisper.cpp not found")))
        )

        controller.onPttPress()
        controller.onPttRelease()

        runBlocking { withTimeout(2000) { waitUntil { controller.state.value is VoiceState.Error } } }
        (controller.state.value as VoiceState.Error).message shouldBe "whisper.cpp not found"
        sendMessageCalls shouldBe 0
    }

    test("a PTT press while a turn is already in flight is ignored") {
        val recorder = FakeAudioRecorder(Files.createTempFile("voice-controller-test", ".wav"))
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, _, _ -> },
            recorder = recorder,
            transcriber = FakeWhisperTranscriber(Result.success("hi"))
        )

        controller.onPttPress()
        controller.onPttPress() // still recording/in-flight — should be ignored

        recorder.startCalls shouldBe 1
    }

    test("a PTT press while only playback is ongoing (turn already ended) calls onBargeIn and starts a new recording") {
        val recorder = FakeAudioRecorder(Files.createTempFile("voice-controller-test", ".wav"))
        var capturedOnTurnEnd: (() -> Unit)? = null
        var bargeInCalls = 0
        val controller = VoiceController(
            sessionId = "s1",
            sendMessage = { _, _, onTurnEnd -> capturedOnTurnEnd = onTurnEnd },
            recorder = recorder,
            transcriber = FakeWhisperTranscriber(Result.success("hi")),
            onBargeIn = { bargeInCalls++ }
        )

        controller.onPttPress()
        controller.onPttRelease()
        runBlocking { withTimeout(2000) { waitUntil { capturedOnTurnEnd != null } } }
        capturedOnTurnEnd!!() // turn ends — releases the in-flight guard, same as CompanionRuntime would

        controller.onPttPress() // barge-in

        recorder.startCalls shouldBe 2
        // onBargeIn() is called unconditionally on every successful press (harmless no-op when
        // nothing's playing), so both the initial press and the barge-in press count here.
        bargeInCalls shouldBe 2
    }
})
