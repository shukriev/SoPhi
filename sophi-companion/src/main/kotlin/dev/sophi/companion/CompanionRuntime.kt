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
    private val ttsEnabled: Boolean = false,
    private val learningHome: java.nio.file.Path =
        java.nio.file.Path.of(System.getProperty("user.home"), ".sophi", "learning"),
    private val consolidationHistoryPath: java.nio.file.Path =
        java.nio.file.Path.of(System.getProperty("user.home"), ".sophi", "memory", "consolidations.jsonl")
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scheduleEngine = sophiRuntime.scheduleEngine(taskStore, runLog, notifier)
    private val sessionStates = mutableMapOf<String, MutableStateFlow<SessionState>>()
    private val transcriptBuilders = mutableMapOf<String, SessionTranscriptBuilder>()
    private val voiceControllers = mutableMapOf<String, dev.sophi.companion.voice.VoiceController>()
    private val speechOutputs = mutableMapOf<String, dev.sophi.companion.voice.SpeechOutput>()
    private val confirmationDeferreds = mutableMapOf<String, CompletableDeferred<Map<String, Boolean>>>()
    // Tool names a session has been told to stop asking about, via "Approve for session" — cleared
    // when the session ends (this map simply never gets an entry for a new session id).
    private val sessionApprovedTools = mutableMapOf<String, MutableSet<String>>()
    private val pendingConfirmationSessionIds = MutableStateFlow<Set<String>>(emptySet())
    private var pollingJob: Job? = null
    private val mcpConfigLoader = dev.sophi.mcp.config.McpConfigLoader()
    private val mcpConfigWriter = dev.sophi.mcp.config.McpConfigWriter()
    private val hubServer = dev.sophi.hub.HubServer(hubPort)
    val remoteSessions = RemoteSessionRegistry()
    private val lessonStore = dev.sophi.learning.LessonStore(dev.sophi.learning.JsonlLog(learningHome.resolve("lessons.jsonl")))
    private val consolidationHistoryStore = dev.sophi.memory.jane.ConsolidationHistoryStore(consolidationHistoryPath)

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
            mcpServers().filter { it.enabled }.forEach { config -> connectMcpServerNotifying(config) }
        }
    }

    /**
     * [SophiRuntime.connectMcpServer] never throws for an ordinary connect failure — the SDK's
     * [dev.sophi.mcp.McpClientManager] already catches spawn/handshake errors internally and logs
     * them to `System.err`, which is invisible for a Dock/Finder-launched app (no terminal to see
     * it in) and easy to miss even from `./gradlew run`. Surface both outcomes here instead, the
     * same way hub-server startup failures already are, so a misconfigured or hung MCP server
     * (locked `--user-data-dir`, stalled first-run `npx -y` fetch, wrong executable path) shows up
     * in the Notifications tab rather than silently leaving tools missing from every chat turn.
     */
    private suspend fun connectMcpServerNotifying(config: dev.sophi.mcp.config.McpServerConfig) {
        // McpClientManager.connectOne catches connect/handshake/listTools failures itself and
        // returns emptyList() rather than throwing (connect-all callers need a broken server to
        // not block the rest) — so the real cause never reaches here as a thrown exception. This
        // callback is the only way to recover it for display instead of falling back to a generic
        // message.
        var cause: Throwable? = null
        runCatching { sophiRuntime.connectMcpServer(config) { e -> cause = e } }
            .onSuccess { tools ->
                if (tools.isEmpty()) {
                    notificationCenter.add(
                        NotificationKind.Mcp, "MCP server '${config.name}' failed to connect",
                        cause?.message
                            ?: "Connected but registered no tools. Check that the server command starts correctly outside sophi-companion too."
                    )
                }
            }
            .onFailure { e ->
                notificationCenter.add(
                    NotificationKind.Mcp, "MCP server '${config.name}' failed to connect",
                    e.message ?: "unknown error"
                )
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

    /** Read-only memory browsing — all empty/no-op when memory isn't enabled for this runtime. */
    fun memoryBrowse(filter: dev.sophi.memory.BrowseFilter = dev.sophi.memory.BrowseFilter()): List<dev.sophi.memory.MemoryView> =
        sophiRuntime.memoryPlugin?.palace()?.browse(filter) ?: emptyList()

    fun memoryThreads(): Map<String, List<String>> = sophiRuntime.memoryPlugin?.palace()?.threads() ?: emptyMap()

    fun memoryProfile(): List<dev.sophi.memory.ProfileAttributeView> =
        sophiRuntime.memoryPlugin?.palace()?.profileView() ?: emptyList()

    /** Active lessons for [scope] (default: current working directory), mirroring `sophi lessons list`. */
    fun lessons(scope: String = System.getProperty("user.dir")): List<dev.sophi.learning.Lesson> = lessonStore.active(scope)

    fun consolidationHistory(): List<dev.sophi.memory.jane.ConsolidationRecord> = consolidationHistoryStore.all()

    suspend fun addOrUpdateMcpServer(config: dev.sophi.mcp.config.McpServerConfig) {
        val current = mcpConfigLoader.load(mcpConfigPath)
        val updated = current.servers.filterNot { it.name == config.name } + config
        mcpConfigWriter.write(mcpConfigPath, current.copy(servers = updated))
        sophiRuntime.disconnectMcpServer(config.name)
        if (config.enabled) connectMcpServerNotifying(config)
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
        if (enabled) connectMcpServerNotifying(updated) else sophiRuntime.disconnectMcpServer(name)
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

    fun pauseTask(taskId: String): Boolean = taskStore.setEnabled(taskId, false)
    fun resumeTask(taskId: String): Boolean = taskStore.setEnabled(taskId, true)
    fun removeTask(taskId: String): Boolean = taskStore.remove(taskId)

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
        val approvedTools = sessionApprovedTools[sessionId] ?: emptySet()
        val (autoApproved, needsPrompt) = requests.partition { it.toolName in approvedTools }
        // Every request already covered by a prior "Approve for session" — skip the card
        // entirely rather than showing a confirmation the user already said to stop asking about.
        if (needsPrompt.isEmpty()) return requests.associate { it.callId to true }

        val state = stateFlowFor(sessionId)
        // .update{} (atomic read-modify-write) rather than .value = .value + x — concurrent
        // sessions each awaiting confirmation race on this same StateFlow, and a plain
        // read-then-write is a lost-update: two sessions can both read the same base set before
        // either write lands, so the second write silently clobbers the first session's id.
        // Updated before state.value so a poller observing NeedsConfirmation also sees this
        // session's id already present in pendingConfirmations.
        pendingConfirmationSessionIds.update { it + sessionId }
        state.value = SessionState.NeedsConfirmation(needsPrompt)
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        confirmationDeferreds[sessionId] = deferred
        val result = deferred.await()
        state.value = SessionState.Running
        return autoApproved.associate { it.callId to true } + result
    }

    fun respondToConfirmation(sessionId: String, approved: Boolean) {
        val requests = (stateFlowFor(sessionId).value as? SessionState.NeedsConfirmation)?.requests ?: return
        pendingConfirmationSessionIds.update { it - sessionId }
        confirmationDeferreds.remove(sessionId)?.complete(requests.associate { it.callId to approved })
    }

    /** Approves the currently pending request(s) and remembers their tool names so this session
     *  stops asking about those specific tools again — until the session ends or companion
     *  restarts. Other sessions, and other tools within this session, are unaffected. */
    fun approveForSession(sessionId: String) {
        val requests = (stateFlowFor(sessionId).value as? SessionState.NeedsConfirmation)?.requests ?: return
        sessionApprovedTools.getOrPut(sessionId) { mutableSetOf() }.addAll(requests.map { it.toolName })
        pendingConfirmationSessionIds.update { it - sessionId }
        confirmationDeferreds.remove(sessionId)?.complete(requests.associate { it.callId to true })
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
