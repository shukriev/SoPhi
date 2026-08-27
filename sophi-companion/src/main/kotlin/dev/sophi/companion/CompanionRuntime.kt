package dev.sophi.companion

import dev.sophi.core.agent.TurnEvent
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.memory.ConsolidationReport
import dev.sophi.sdk.SophiRuntime
import dev.sophi.skills.InstallResult
import dev.sophi.skills.Skill
import dev.sophi.schedule.notify.Notifier
import dev.sophi.schedule.store.RunLog
import dev.sophi.schedule.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun consolidationNotificationBody(report: ConsolidationReport): String? =
    if (report.total == 0) null else
        "Consolidated: merged=${report.merged} strengthened=${report.strengthened} " +
            "compressed=${report.compressed} pruned=${report.pruned} purged=${report.purged}"

class CompanionRuntime(
    private val sophiRuntime: SophiRuntime,
    val sessionManager: dev.sophi.core.session.SessionManager,
    private val mcpConfigPath: java.nio.file.Path,
    private val taskStore: TaskStore,
    private val runLog: RunLog,
    notifier: Notifier,
    val notificationCenter: NotificationCenter,
    hubPort: Int = 8765,
    private val voiceConfig: dev.sophi.companion.voice.VoiceConfig? = null,
    private val sttEnabled: Boolean = false,
    private val ttsEnabled: Boolean = false
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scheduleEngine = sophiRuntime.scheduleEngine(taskStore, runLog, notifier)
    private val sessionStates = mutableMapOf<String, MutableStateFlow<SessionState>>()
    private val transcriptBuilders = mutableMapOf<String, SessionTranscriptBuilder>()
    private val voiceControllers = mutableMapOf<String, dev.sophi.companion.voice.VoiceController>()
    private val speechOutputs = mutableMapOf<String, dev.sophi.companion.voice.SpeechOutput>()
    private val confirmationDeferreds = mutableMapOf<String, CompletableDeferred<Map<String, Boolean>>>()
    private val pendingConfirmationSessionIds = MutableStateFlow<Set<String>>(emptySet())
    private var pollingJob: Job? = null
    private val mcpConfigLoader = dev.sophi.mcp.config.McpConfigLoader()
    private val mcpConfigWriter = dev.sophi.mcp.config.McpConfigWriter()
    private val hubServer = dev.sophi.hub.HubServer(hubPort)
    val remoteSessions = RemoteSessionRegistry()

    init {
        // Scope note (narrower than connecting as a client to an existing hub for a stale
        // previous instance): a second companion instance must not crash on a bind conflict,
        // but becoming a client of someone else's hub duplicates this feature's plumbing for an
        // edge case. v1 degrades safely instead — local sessions keep working, remote CLI
        // monitoring is just unavailable for this instance.
        runCatching { hubServer.start() }
            .onFailure { System.err.println("sophi-companion: hub unavailable (${it.message}) — remote CLI sessions will not appear") }
        scope.launch {
            hubServer.events.collect { event ->
                remoteSessions.onEvent(event)
                if (event is dev.sophi.hub.HubEvent.ScheduleNotification) {
                    notificationCenter.add(NotificationKind.Schedule, event.title, event.body)
                }
            }
        }
        scope.launch {
            mcpServers().filter { it.enabled }.forEach { config ->
                runCatching { sophiRuntime.connectMcpServer(config) }
            }
        }
    }

    fun isRemote(sessionId: String): Boolean = sessionId in remoteSessions.remoteSessionIds()

    suspend fun sendRemoteMessage(sessionId: String, text: String) {
        hubServer.sendCommand(dev.sophi.hub.HubCommand.SendMessage(sessionId, text))
    }

    suspend fun respondToRemoteConfirmation(sessionId: String, callId: String, approved: Boolean) {
        hubServer.sendCommand(dev.sophi.hub.HubCommand.ConfirmationResponse(sessionId, callId, approved))
    }

    fun mcpServers(): List<dev.sophi.mcp.config.McpServerConfig> = mcpConfigLoader.load(mcpConfigPath).servers

    fun skills(): List<Pair<String, Skill>> = sophiRuntime.skills()
    fun installSkill(source: String): InstallResult = sophiRuntime.installSkill(source)
    fun removeSkill(id: String): Boolean = sophiRuntime.removeSkill(id)

    suspend fun addOrUpdateMcpServer(config: dev.sophi.mcp.config.McpServerConfig) {
        val current = mcpConfigLoader.load(mcpConfigPath)
        val updated = current.servers.filterNot { it.name == config.name } + config
        mcpConfigWriter.write(mcpConfigPath, current.copy(servers = updated))
        sophiRuntime.disconnectMcpServer(config.name)
        if (config.enabled) sophiRuntime.connectMcpServer(config)
    }

    suspend fun removeMcpServer(name: String) {
        val current = mcpConfigLoader.load(mcpConfigPath)
        mcpConfigWriter.write(mcpConfigPath, current.copy(servers = current.servers.filterNot { it.name == name }))
        sophiRuntime.disconnectMcpServer(name)
    }

    suspend fun setMcpServerEnabled(name: String, enabled: Boolean) {
        val current = mcpConfigLoader.load(mcpConfigPath)
        val config = current.servers.find { it.name == name } ?: return
        val updated = config.copy(enabled = enabled)
        mcpConfigWriter.write(mcpConfigPath, current.copy(servers = current.servers.map { if (it.name == name) updated else it }))
        if (enabled) sophiRuntime.connectMcpServer(updated) else sophiRuntime.disconnectMcpServer(name)
    }

    fun tasks(): List<dev.sophi.schedule.model.ScheduledTask> = taskStore.list()

    fun createTask(
        name: String,
        prompt: String,
        mode: dev.sophi.schedule.model.TaskMode = dev.sophi.schedule.model.TaskMode.Recurring,
        trigger: dev.sophi.schedule.model.Trigger = dev.sophi.schedule.model.Trigger.Manual
    ): dev.sophi.schedule.model.ScheduledTask =
        taskStore.add(dev.sophi.schedule.model.ScheduledTask(name = name, trigger = trigger, mode = mode, prompt = prompt))

    fun runHistory(taskId: String): List<dev.sophi.schedule.model.RunRecord> = runLog.forTask(taskId)

    suspend fun runTaskNow(taskId: String) { scheduleEngine.runNow(taskId) }

    private fun stateFlowFor(sessionId: String): MutableStateFlow<SessionState> =
        sessionStates.getOrPut(sessionId) { MutableStateFlow(SessionState.Idle) }

    // ponytail: session-history seeding reads+parses the session's .jsonl file synchronously on
    // whichever thread calls this (Compose UI thread on first ChatTab access, or sendMessage's
    // caller) — a real if usually brief UI stall for large histories. Not moved to a background
    // dispatcher here: doing that safely needs startTurn to wait for any in-flight seed rather
    // than racing it (seed() is a no-op once the transcript is non-empty, so an unlucky ordering
    // could silently drop the persisted history instead of just being slow). Revisit with that
    // synchronization if session files large enough to cause a noticeable stall turn up in
    // practice.
    private fun transcriptBuilderFor(sessionId: String): SessionTranscriptBuilder =
        transcriptBuilders.getOrPut(sessionId) {
            SessionTranscriptBuilder().also { builder ->
                runCatching { sessionManager.load(sessionId) }.onSuccess { session ->
                    runCatching { SessionTranscriptBuilder.entriesFor(session.entries) }
                        .onSuccess { builder.seed(it) }
                        .onFailure { System.err.println("sophi-companion: failed to parse persisted history for $sessionId (${it.message})") }
                }
            }
        }

    fun sessionState(sessionId: String): StateFlow<SessionState> = stateFlowFor(sessionId)

    /** A session's turn transcript, in order — see TranscriptEntry for the shape of each entry. */
    fun sessionMessages(sessionId: String): StateFlow<List<TranscriptEntry>> = transcriptBuilderFor(sessionId).transcript

    /** Speech-to-text's per-session controller, or null unless both [dev.sophi.companion.
     *  CompanionSettings.sttEnabled] and voice tools are installed ([voiceConfig] non-null). */
    fun voiceController(sessionId: String): dev.sophi.companion.voice.VoiceController? {
        val config = voiceConfig?.takeIf { sttEnabled } ?: return null
        return voiceControllers.getOrPut(sessionId) {
            dev.sophi.companion.voice.VoiceController(
                sessionId = sessionId,
                sendMessage = { id, text, onTurnEnd -> sendMessage(id, text, onTurnEnd) },
                recorder = dev.sophi.companion.voice.JavaSoundAudioRecorder(),
                transcriber = dev.sophi.companion.voice.ProcessWhisperTranscriber(config),
                onBargeIn = { speechOutput(sessionId)?.stopSpeaking() }
            )
        }
    }

    /** Text-to-speech's per-session player, or null unless both [dev.sophi.companion.
     *  CompanionSettings.ttsEnabled] and voice tools are installed ([voiceConfig] non-null).
     *  [sendMessage] feeds every reply's tokens into this directly, so a turn gets spoken back
     *  regardless of whether it was typed or sent via [voiceController]. */
    fun speechOutput(sessionId: String): dev.sophi.companion.voice.SpeechOutput? {
        val config = voiceConfig?.takeIf { ttsEnabled } ?: return null
        return speechOutputs.getOrPut(sessionId) {
            dev.sophi.companion.voice.SpeechOutput(
                synthesizer = dev.sophi.companion.voice.ProcessPiperSynthesizer(config),
                player = dev.sophi.companion.voice.JavaSoundAudioPlayer()
            )
        }
    }

    /** Ids of sessions currently awaiting confirmation, across all sessions. */
    val pendingConfirmations: StateFlow<Set<String>> = pendingConfirmationSessionIds

    suspend fun newSession(title: String? = null): String = sophiRuntime.newSession(title)

    fun sendMessage(
        sessionId: String,
        input: String,
        onTurnEnd: () -> Unit = {}
    ) {
        val state = stateFlowFor(sessionId)
        val builder = transcriptBuilderFor(sessionId)
        val speech = speechOutput(sessionId)
        builder.startTurn(input)
        state.value = SessionState.Running
        scope.launch(SessionIdContext(sessionId)) {
            try {
                sophiRuntime.streamTurn(sessionId, input) { event ->
                    when (event) {
                        is TurnEvent.Token -> { builder.onToken(event.text); speech?.onToken(event.text) }
                        is TurnEvent.ReasoningToken -> builder.onReasoningToken(event.text)
                        is TurnEvent.ToolCallStarted -> builder.onToolCallStarted(event.name, event.argsJson)
                        is TurnEvent.ToolCallFinished -> builder.onToolCallFinished(event.name, event.result, event.isError)
                        else -> Unit // ConfirmationStarted/Finished — confirmation flow is unrelated, unchanged
                    }
                }
                builder.endTurn()
                state.value = SessionState.Idle
                speech?.onTurnEnd()
                onTurnEnd()
            } catch (e: Exception) {
                builder.endTurn()
                state.value = SessionState.Error(e.message ?: "unknown error")
                speech?.onTurnEnd()
                onTurnEnd()
            }
        }
    }

    suspend fun awaitConfirmation(sessionId: String, requests: List<ConfirmationRequest>): Map<String, Boolean> {
        val state = stateFlowFor(sessionId)
        // .update{} (atomic read-modify-write) rather than .value = .value + x — concurrent
        // sessions each awaiting confirmation race on this same StateFlow, and a plain
        // read-then-write is a lost-update: two sessions can both read the same base set before
        // either write lands, so the second write silently clobbers the first session's id.
        // Updated before state.value so a poller observing NeedsConfirmation also sees this
        // session's id already present in pendingConfirmations.
        pendingConfirmationSessionIds.update { it + sessionId }
        state.value = SessionState.NeedsConfirmation(requests)
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        confirmationDeferreds[sessionId] = deferred
        val result = deferred.await()
        state.value = SessionState.Running
        return result
    }

    fun respondToConfirmation(sessionId: String, approved: Boolean) {
        val requests = (stateFlowFor(sessionId).value as? SessionState.NeedsConfirmation)?.requests ?: return
        pendingConfirmationSessionIds.update { it - sessionId }
        confirmationDeferreds.remove(sessionId)?.complete(requests.associate { it.callId to approved })
    }

    fun startSchedulePolling(intervalMs: Long = 30_000) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                scheduleEngine.tickOnce()
                // A memory-store read failure here must not take scheduled-task polling down with it.
                try {
                    sophiRuntime.memoryPlugin?.consolidateIfDue()?.let { report ->
                        consolidationNotificationBody(report)?.let { body ->
                            notificationCenter.add(NotificationKind.Memory, "Sophi memory", body)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best-effort; consolidation just retries on the next poll tick.
                }
                delay(intervalMs)
            }
        }
    }

    fun close() {
        pollingJob?.cancel()
        hubServer.stop()
        scope.cancel()
        sophiRuntime.close()
    }
}
