package dev.sophi.companion

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
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
import kotlin.io.path.createTempDirectory

// AgentLoop.streamTurn calls provider.stream(), not complete() — complete() is unused by this
// path but still required to satisfy the interface.
private class SlowFakeProvider(private val delayMs: Long) : LLMProvider {
    override val name = "fake"

    override suspend fun complete(request: CompletionRequest): LLMResponse =
        LLMResponse.Text(content = "done", usage = TokenUsage(0, 0))

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        delay(delayMs)
        emit(StreamEvent.Content("done"))
    }
}

class CompanionRuntimeTest : FunSpec({
    test("two sessions started at the same time both reach Idle, proving they ran concurrently not sequentially") {
        val dir = createTempDirectory("companion-runtime-test")
        val sophiRuntime = Sophi.runtime {
            provider = SlowFakeProvider(delayMs = 200)
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
        }
        val runtime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier
        )
        val sessionA = runBlocking { sophiRuntime.newSession() }
        val sessionB = runBlocking { sophiRuntime.newSession() }

        val startedAtMs = System.currentTimeMillis()
        runtime.sendMessage(sessionA, "hi")
        runtime.sendMessage(sessionB, "hi")

        runBlocking {
            waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionA).value == SessionState.Idle }
            waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionB).value == SessionState.Idle }
        }
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        // Sequential execution would take >= 400ms (2 x 200ms); concurrent execution finishes near 200ms.
        (elapsedMs < 350) shouldBe true
    }

    test("sendMessage sets state to Running immediately, then Idle when the turn completes") {
        val dir = createTempDirectory("companion-runtime-test")
        val sophiRuntime = Sophi.runtime {
            provider = SlowFakeProvider(delayMs = 100)
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
        }
        val runtime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier
        )
        val sessionId = runBlocking { sophiRuntime.newSession() }

        runtime.sendMessage(sessionId, "hi")
        val stateRightAfterSend = runtime.sessionState(sessionId).value

        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionId).value == SessionState.Idle } }

        stateRightAfterSend shouldBe SessionState.Running
    }

    test("sessionState() for a session that was never sent a message starts as Idle") {
        val dir = createTempDirectory("companion-runtime-test")
        val sophiRuntime = Sophi.runtime {
            provider = SlowFakeProvider(delayMs = 0)
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
        }
        val runtime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier
        )

        runtime.sessionState("never-sent-to").value shouldBe SessionState.Idle
    }

    test("sendMessage records the user line immediately and the reply once the turn completes") {
        val dir = createTempDirectory("companion-runtime-test")
        val sophiRuntime = Sophi.runtime {
            provider = SlowFakeProvider(delayMs = 50)
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
        }
        val runtime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier
        )
        val sessionId = runBlocking { sophiRuntime.newSession() }

        runtime.sendMessage(sessionId, "hi")
        val messagesRightAfterSend = runtime.sessionMessages(sessionId).value

        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionId).value == SessionState.Idle } }

        messagesRightAfterSend shouldBe listOf("you: hi")
        runtime.sessionMessages(sessionId).value shouldBe listOf("you: hi", "sophi: done")
    }
})

private suspend fun waitUntil(timeoutMs: Long, poll: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (poll()) return
        delay(10)
    }
    error("waitUntil timed out after ${timeoutMs}ms")
}
