package dev.sophi.companion

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.sdk.Sophi
import dev.sophi.schedule.notify.NoopNotifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
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

// Emits multiple StreamEvent chunks per turn, so AgentLoop.streamTurn's per-chunk TurnEvent.Token/
// ReasoningToken emission is actually exercised — SlowFakeProvider/ConfirmingFakeProvider each emit
// exactly one chunk, which isn't enough to distinguish "streamed incrementally" from "atomic".
private class ChunkedStreamingFakeProvider : LLMProvider {
    override val name = "fake"

    override suspend fun complete(request: CompletionRequest): LLMResponse =
        LLMResponse.Text(content = "done", usage = TokenUsage(0, 0))

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        emit(StreamEvent.Reasoning("thinking"))
        emit(StreamEvent.Reasoning("..."))
        emit(StreamEvent.Content("Hel"))
        emit(StreamEvent.Content("lo!"))
    }
}

// AgentLoop.turn() delegates entirely to streamTurn(), which reads provider.stream(), not
// complete() — complete() is unused by this path but still required to satisfy the interface.
// stream() emits ToolCallsReady on the first call of a turn (no TOOL message in history yet)
// and plain Content once the tool round has executed and produced a TOOL message — this makes
// it correctly per-session/per-turn with no shared mutable counter, safe under concurrent
// sessions.
private class ConfirmingFakeProvider : LLMProvider {
    override val name = "fake"
    override suspend fun complete(request: CompletionRequest): LLMResponse =
        LLMResponse.Text(content = "done", usage = TokenUsage(0, 0))

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        if (request.messages.any { it.role == MessageRole.TOOL }) {
            emit(StreamEvent.Content("done"))
        } else {
            emit(StreamEvent.ToolCallsReady(listOf(ToolCall("call-1", "risky_tool", "{}"))))
        }
    }
}

private class RiskyFakeTool : Tool {
    override val name = "risky_tool"
    override val description = "a tool that always needs confirmation"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun riskLevel(argumentsJson: String) = RiskLevel.DESTRUCTIVE
    override suspend fun execute(argumentsJson: String) = "executed"
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
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
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
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
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
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
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
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )
        val sessionId = runBlocking { sophiRuntime.newSession() }

        runtime.sendMessage(sessionId, "hi")
        val messagesRightAfterSend = runtime.sessionMessages(sessionId).value

        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionId).value == SessionState.Idle } }

        messagesRightAfterSend shouldBe listOf(TranscriptEntry.UserMessage(0, "hi"))
        runtime.sessionMessages(sessionId).value shouldBe listOf(
            TranscriptEntry.UserMessage(0, "hi"),
            TranscriptEntry.Answer(1, "done")
        )
    }

    test("sendMessage streams reasoning and answer tokens incrementally, not as one atomic jump") {
        val dir = createTempDirectory("companion-runtime-test")
        val sophiRuntime = Sophi.runtime {
            provider = ChunkedStreamingFakeProvider()
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
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )
        val sessionId = runBlocking { sophiRuntime.newSession() }

        runtime.sendMessage(sessionId, "hi")
        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionId).value == SessionState.Idle } }

        runtime.sessionMessages(sessionId).value shouldBe listOf(
            TranscriptEntry.UserMessage(0, "hi"),
            TranscriptEntry.Reasoning(1, "thinking..."),
            TranscriptEntry.Answer(2, "Hello!")
        )
    }

    test("a tool call that needs confirmation sets NeedsConfirmation, then resumes once respondToConfirmation is called") {
        val dir = createTempDirectory("companion-runtime-test")
        lateinit var runtime: CompanionRuntime
        val sophiRuntime = Sophi.runtime {
            provider = ConfirmingFakeProvider()
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
            tool(RiskyFakeTool())
            confirmationPolicy(GuiConfirmationPolicy(
                notify = { _, _ -> },
                onConfirmationNeeded = { sessionId, requests -> runtime.awaitConfirmation(sessionId, requests) }
            ))
        }
        runtime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )
        val sessionId = runBlocking { sophiRuntime.newSession() }

        runtime.sendMessage(sessionId, "do the risky thing")
        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionId).value is SessionState.NeedsConfirmation } }
        val pending = runtime.sessionState(sessionId).value as SessionState.NeedsConfirmation
        pending.requests.map { it.toolName } shouldBe listOf("risky_tool")

        runtime.respondToConfirmation(sessionId, true)

        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionId).value == SessionState.Idle } }
        (runtime.sessionMessages(sessionId).value.last() as TranscriptEntry.Answer).text shouldBe "done"
    }

    test("respondToConfirmation is a no-op when nothing is pending for that session") {
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
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )

        runtime.respondToConfirmation("never-had-a-pending-confirmation", true)  // must not throw
    }

    test("two sessions each awaiting confirmation resolve independently, no cross-talk") {
        val dir = createTempDirectory("companion-runtime-test")
        lateinit var runtime: CompanionRuntime
        val sophiRuntime = Sophi.runtime {
            provider = ConfirmingFakeProvider()
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
            tool(RiskyFakeTool())
            confirmationPolicy(GuiConfirmationPolicy(
                notify = { _, _ -> },
                onConfirmationNeeded = { sessionId, requests -> runtime.awaitConfirmation(sessionId, requests) }
            ))
        }
        runtime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )
        val sessionA = runBlocking { sophiRuntime.newSession() }
        val sessionB = runBlocking { sophiRuntime.newSession() }

        runtime.sendMessage(sessionA, "a")
        runtime.sendMessage(sessionB, "b")
        runBlocking {
            waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionA).value is SessionState.NeedsConfirmation }
            waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionB).value is SessionState.NeedsConfirmation }
        }

        runtime.respondToConfirmation(sessionA, false)
        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionA).value == SessionState.Idle } }
        (runtime.sessionState(sessionB).value is SessionState.NeedsConfirmation) shouldBe true

        runtime.respondToConfirmation(sessionB, true)
        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionB).value == SessionState.Idle } }
    }

    test("pendingConfirmations includes a session's id while it needs confirmation, then removes it once resolved") {
        val dir = createTempDirectory("companion-runtime-test")
        lateinit var runtime: CompanionRuntime
        val sophiRuntime = Sophi.runtime {
            provider = ConfirmingFakeProvider()
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
            tool(RiskyFakeTool())
            confirmationPolicy(GuiConfirmationPolicy(
                notify = { _, _ -> },
                onConfirmationNeeded = { sessionId, requests -> runtime.awaitConfirmation(sessionId, requests) }
            ))
        }
        runtime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )
        val sessionId = runBlocking { sophiRuntime.newSession() }

        runtime.pendingConfirmations.value shouldBe emptySet()

        runtime.sendMessage(sessionId, "do the risky thing")
        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionId).value is SessionState.NeedsConfirmation } }
        runtime.pendingConfirmations.value shouldBe setOf(sessionId)

        runtime.respondToConfirmation(sessionId, true)
        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionId).value == SessionState.Idle } }
        runtime.pendingConfirmations.value shouldBe emptySet()
    }

    test("pendingConfirmations tracks two sessions independently") {
        val dir = createTempDirectory("companion-runtime-test")
        lateinit var runtime: CompanionRuntime
        val sophiRuntime = Sophi.runtime {
            provider = ConfirmingFakeProvider()
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
            tool(RiskyFakeTool())
            confirmationPolicy(GuiConfirmationPolicy(
                notify = { _, _ -> },
                onConfirmationNeeded = { sessionId, requests -> runtime.awaitConfirmation(sessionId, requests) }
            ))
        }
        runtime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )
        val sessionA = runBlocking { sophiRuntime.newSession() }
        val sessionB = runBlocking { sophiRuntime.newSession() }

        runtime.sendMessage(sessionA, "a")
        runtime.sendMessage(sessionB, "b")
        runBlocking {
            waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionA).value is SessionState.NeedsConfirmation }
            waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionB).value is SessionState.NeedsConfirmation }
        }
        runtime.pendingConfirmations.value shouldBe setOf(sessionA, sessionB)

        runtime.respondToConfirmation(sessionA, false)
        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionA).value == SessionState.Idle } }
        runtime.pendingConfirmations.value shouldBe setOf(sessionB)

        runtime.respondToConfirmation(sessionB, true)
        runBlocking { waitUntil(timeoutMs = 2000) { runtime.sessionState(sessionB).value == SessionState.Idle } }
        runtime.pendingConfirmations.value shouldBe emptySet()
    }

    test("sessionMessages replays a persisted session's prior turns without sending anything") {
        val dir = createTempDirectory("companion-runtime-test")
        val sessionsDir = dir.resolve("sessions")
        val sophiRuntime = Sophi.runtime {
            provider = SlowFakeProvider(delayMs = 0)
            model = "fake-model"
            contextWindowTokens(200_000)
            this.sessionsDir = sessionsDir
        }
        val firstRuntime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(sessionsDir),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )
        val sessionId = runBlocking { sophiRuntime.newSession() }
        firstRuntime.sendMessage(sessionId, "hi")
        runBlocking { waitUntil(timeoutMs = 2000) { firstRuntime.sessionState(sessionId).value == SessionState.Idle } }
        firstRuntime.close()

        // A fresh runtime/companion window over the same sessions dir — nothing sent yet on this
        // instance, but the session already has turns on disk from before.
        val reopenedSophiRuntime = Sophi.runtime {
            provider = SlowFakeProvider(delayMs = 0)
            model = "fake-model"
            contextWindowTokens(200_000)
            this.sessionsDir = sessionsDir
        }
        val reopenedRuntime = CompanionRuntime(
            sophiRuntime = reopenedSophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(sessionsDir),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )

        reopenedRuntime.sessionMessages(sessionId).value shouldBe listOf(
            TranscriptEntry.UserMessage(0, "hi"),
            TranscriptEntry.Answer(1, "done")
        )
    }

    test("sessionMessages doesn't crash when a persisted session has malformed tool-call metadata") {
        val dir = createTempDirectory("companion-runtime-test")
        val sessionsDir = dir.resolve("sessions")
        java.nio.file.Files.createDirectories(sessionsDir)
        val sessionId = "malformed-session"
        val entry = dev.sophi.core.session.SessionEntry(
            id = "e1",
            role = dev.sophi.core.session.EntryRole.ASSISTANT,
            content = "",
            timestamp = 1L,
            metadata = mapOf("toolCalls" to "not-json")
        )
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        java.nio.file.Files.writeString(
            sessionsDir.resolve("$sessionId.jsonl"),
            json.encodeToString(dev.sophi.core.session.SessionEntry.serializer(), entry)
        )

        val sophiRuntime = Sophi.runtime {
            provider = SlowFakeProvider(delayMs = 0)
            model = "fake-model"
            contextWindowTokens(200_000)
            this.sessionsDir = sessionsDir
        }
        val runtime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(sessionsDir),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        )

        runtime.sessionMessages(sessionId).value shouldBe emptyList()
    }

    test("a CLI session registered via the hub appears in remoteSessions and can receive a message") {
        val dir = createTempDirectory("companion-runtime-test")
        val hubPort = freePort()
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
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json"))),
            hubPort = hubPort
        )
        runBlocking {
            withTimeout(5000) {
                val client = dev.sophi.hub.HubClient(hubPort, sessionId = "cli-1")
                client.connect(this)
                client.publish(dev.sophi.hub.HubEvent.SessionRegistered("cli-1", "remote", 1L, "/repo"))
                waitUntil(timeoutMs = 2000) { runtime.remoteSessions.remoteSessionIds() == setOf("cli-1") }

                runtime.isRemote("cli-1") shouldBe true
                runtime.isRemote("some-local-id") shouldBe false

                val received = async { client.commands.first() }
                delay(100)
                runtime.sendRemoteMessage("cli-1", "hello from companion")
                received.await() shouldBe dev.sophi.hub.HubCommand.SendMessage("cli-1", "hello from companion")

                client.close()
            }
        }
        runtime.close()
    }

    test("respondToRemoteConfirmation publishes a ConfirmationResponse for the right session and callId") {
        val dir = createTempDirectory("companion-runtime-test")
        val hubPort = freePort()
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
            notifier = NoopNotifier,
            notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json"))),
            hubPort = hubPort
        )
        runBlocking {
            withTimeout(5000) {
                val client = dev.sophi.hub.HubClient(hubPort, sessionId = "cli-1")
                client.connect(this)
                client.publish(dev.sophi.hub.HubEvent.SessionRegistered("cli-1", null, 1L, "/repo"))
                delay(200)

                val received = async { client.commands.first() }
                delay(100)
                runtime.respondToRemoteConfirmation("cli-1", callId = "c1", approved = true)
                received.await() shouldBe dev.sophi.hub.HubCommand.ConfirmationResponse("cli-1", "c1", true)

                client.close()
            }
        }
        runtime.close()
    }

    test("a ScheduleNotification event received over the hub is added to the notification center") {
        val dir = createTempDirectory("companion-runtime-test")
        val hubPort = freePort()
        val sophiRuntime = Sophi.runtime {
            provider = SlowFakeProvider(delayMs = 0)
            model = "fake-model"
            contextWindowTokens(200_000)
            sessionsDir = dir.resolve("sessions")
        }
        val notificationCenter = NotificationCenter(NotificationStore(dir.resolve("notifications.json")))
        val runtime = CompanionRuntime(
            sophiRuntime = sophiRuntime,
            sessionManager = dev.sophi.core.session.FileSessionManager(dir.resolve("sessions")),
            mcpConfigPath = dir.resolve("mcp.json"),
            taskStore = TaskStore(dir.resolve("tasks.json")),
            runLog = RunLog(dir.resolve("runs.jsonl")),
            notifier = NoopNotifier,
            notificationCenter = notificationCenter,
            hubPort = hubPort
        )
        runBlocking {
            withTimeout(5000) {
                val client = dev.sophi.hub.HubClient(hubPort, sessionId = "task-1")
                client.connect(this)
                client.publish(dev.sophi.hub.HubEvent.ScheduleNotification("task-1", "Sophi: t", "completed — ok"))
                waitUntil(timeoutMs = 2000) { notificationCenter.records.value.isNotEmpty() }
                client.close()
            }
        }
        notificationCenter.records.value.single().title shouldBe "Sophi: t"
        runtime.close()
    }
})

private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

private suspend fun waitUntil(timeoutMs: Long, poll: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (poll()) return
        delay(10)
    }
    error("waitUntil timed out after ${timeoutMs}ms")
}
