package dev.sophi.companion

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.companion.voice.AudioRecorder
import dev.sophi.companion.voice.VoiceConfig
import dev.sophi.companion.voice.WhisperTranscriber
import dev.sophi.companion.voice.buildAmbientReminderPrompt
import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin
import dev.sophi.sdk.Sophi
import dev.sophi.schedule.notify.NoopNotifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile

// Mirrors CompanionRuntimeTest.kt's SlowFakeProvider — complete() is unused by the turn path but
// required to satisfy the interface.
private class NoopFakeProvider : LLMProvider {
    override val name = "fake"
    override suspend fun complete(request: CompletionRequest): LLMResponse =
        LLMResponse.Text(content = "done", usage = TokenUsage(0, 0))
    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow { emit(StreamEvent.Content("done")) }
}

private class CapturingFakeProvider : LLMProvider {
    override val name = "fake"
    val capturedPrompts = mutableListOf<String>()
    override suspend fun complete(request: CompletionRequest): LLMResponse =
        LLMResponse.Text(content = "done", usage = TokenUsage(0, 0))
    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        // The turn's own prompt is the last message in the request built for this call — if this
        // assumption is wrong for how AgentLoop shapes a first-turn CompletionRequest, this test
        // will fail with a clear mismatch (capturedPrompts.first() vs the expected prompt text)
        // rather than silently passing; adjust the index/extraction here, not the production code.
        capturedPrompts.add(request.messages.last().content)
        emit(StreamEvent.Content("done"))
    }
}

private class FakeAmbientRecorder : AudioRecorder {
    val wav: Path = createTempFile("ambient-test", ".wav")
    override fun start() {}
    override fun stop(): Path = wav
}

private class ScriptedAmbientTranscriber(private val results: MutableList<Result<String>>) : WhisperTranscriber {
    override suspend fun transcribe(wavFile: Path): Result<String> =
        if (results.isEmpty()) Result.success("") else results.removeAt(0)
}

private suspend fun waitUntil(timeoutMs: Long = 3000, poll: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) { if (poll()) return; delay(10) }
    error("waitUntil timed out after ${timeoutMs}ms")
}

class CompanionRuntimeAmbientTest : FunSpec({
    test("a reminder-shaped batch settles ambient=true on the main runtime and turns the reminder runtime") {
        val dir = createTempDirectory("companion-runtime-ambient-test")
        val settledAfterTurn = mutableListOf<HookContext>()
        val settleSpy = object : SophiPlugin {
            override val name = "settle-spy"
            override fun hooks() = listOf(object : AgentHook {
                override val point = HookPoint.AFTER_TURN
                override suspend fun invoke(context: HookContext) { settledAfterTurn.add(context) }
            })
        }
        val sophiRuntime = Sophi.runtime {
            provider = NoopFakeProvider()
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
            plugin(settleSpy)
        }
        val companionRuntime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )
        val reminderProvider = CapturingFakeProvider()
        val ambientReminderRuntime = Sophi.runtime {
            provider = reminderProvider
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("reminder-sessions")
        }
        val recorder = FakeAmbientRecorder()
        val transcriber = ScriptedAmbientTranscriber(mutableListOf(Result.success("remind me to call the dentist")))

        companionRuntime.startAmbientListening(
            ambientReminderRuntime = ambientReminderRuntime,
            voiceConfig = VoiceConfig("w", "m", "p", "v"),
            clipMs = 1, flushIntervalMs = 1, pollMs = 1,
            recorder = recorder, transcriber = transcriber
        )

        runBlocking {
            waitUntil { settledAfterTurn.isNotEmpty() }
            waitUntil { reminderProvider.capturedPrompts.isNotEmpty() }
        }
        companionRuntime.stopAmbientListening()

        settledAfterTurn.first().let {
            it.userInput shouldBe "remind me to call the dentist"
            it.assistantReply shouldBe ""
            it.ambient shouldBe true
        }
        reminderProvider.capturedPrompts.first() shouldBe
            buildAmbientReminderPrompt("remind me to call the dentist")
    }

    test("a recorder that always fails to start notifies once instead of silently killing the loop") {
        val dir = createTempDirectory("companion-runtime-ambient-test")
        val sophiRuntime = Sophi.runtime {
            provider = NoopFakeProvider()
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
        }
        val notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        val companionRuntime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier,
            notificationCenter = notificationCenter
        )
        val ambientReminderRuntime = Sophi.runtime {
            provider = NoopFakeProvider()
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("reminder-sessions")
        }
        var startCalls = 0
        val failingRecorder = object : AudioRecorder {
            override fun start() { startCalls++; error("microphone unavailable") }
            override fun stop(): Path = error("never reached — start() always throws first")
        }

        companionRuntime.startAmbientListening(
            ambientReminderRuntime = ambientReminderRuntime,
            voiceConfig = VoiceConfig("w", "m", "p", "v"),
            clipMs = 1, flushIntervalMs = 1, pollMs = 1,
            recorder = failingRecorder, transcriber = ScriptedAmbientTranscriber(mutableListOf())
        )

        runBlocking {
            waitUntil { notificationCenter.records.value.isNotEmpty() }
            // Proves the loop survives the failure and keeps retrying, rather than dying after one throw.
            waitUntil { startCalls >= 2 }
        }
        companionRuntime.stopAmbientListening()

        // Notified once, not spammed on every retry.
        notificationCenter.records.value.size shouldBe 1
    }
})
