package dev.sophi.companion

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.sophi.ai.providers.ProviderConfigException
import dev.sophi.ai.providers.buildProviderFromType
import dev.sophi.companion.ui.AppShell
import dev.sophi.sdk.Sophi
import dev.sophi.schedule.notify.NotificationText
import dev.sophi.schedule.notify.Notifier
import java.nio.file.Path

private fun buildRuntime(settings: CompanionSettings, apiKey: String?): CompanionRuntime {
    settings.validationError()?.let { error("Invalid ~/.sophi/companion.json: $it") }
    val tasksDir = Path.of(System.getProperty("user.home"), ".sophi", "companion")
    val notificationCenter = NotificationCenter(NotificationStore(tasksDir.resolve("notifications.json")))
    val provider = try {
        buildProviderFromType(
            settings.providerType, apiKey, settings.baseUrl, settings.model,
            missingApiKeyMessage = "apiKey is required for provider type claude",
            missingBaseUrlMessage = "baseUrl is required for provider type openai-compat"
        )
    } catch (e: ProviderConfigException) {
        error(e.message ?: "Invalid provider configuration")
    }
    lateinit var companionRuntime: CompanionRuntime
    val sophiRuntime = Sophi.runtime {
        this.provider = provider
        model = settings.model
        maxTokens = settings.maxTokens
        contextWindowTokens(settings.contextWindowTokens)
        sessionsDir = Path.of(settings.sessionsDir)
        // Deliberately not calling .mcpConfig(path) here: Task 13 connects only the servers
        // marked enabled in .sophi/mcp.json, via SophiRuntime.connectMcpServer, instead of
        // RuntimeBuilder's own unconditional "connect everything in the file" behavior.
        confirmationPolicy(GuiConfirmationPolicy(
            notify = { t, b -> notificationCenter.add(NotificationKind.Confirmation, t, b) },
            // Routed per session via SessionIdContext (see CompanionRuntime.sendMessage):
            // awaitConfirmation sets that session's state to NeedsConfirmation and suspends
            // until the matching Chat tab's Approve/Deny calls respondToConfirmation.
            onConfirmationNeeded = { sessionId, requests -> companionRuntime.awaitConfirmation(sessionId, requests) }
        ))
        // settings.validationError() (checked above) already guarantees embeddingModel/
        // embeddingBaseUrl are non-blank whenever memoryEnabled is true.
        if (settings.memoryEnabled) {
            memory(
                settings.embeddingModel!!, settings.embeddingBaseUrl!!, settings.embeddingApiKey,
                settings.embeddingDimensions,
                onWarning = { msg -> notificationCenter.add(NotificationKind.Memory, "Sophi memory", msg) }
            )
        }
        agentsDir(
            Path.of(settings.agentsDir),
            onWarning = { msg -> notificationCenter.add(NotificationKind.Memory, "Agent definitions", msg) }
        )
    }
    val scheduleNotifier = Notifier { task, run ->
        val (title, body) = NotificationText.forTaskRun(task, run)
        notificationCenter.add(NotificationKind.Schedule, title, body)
    }
    // settings.validationError() (checked above) already guarantees the four path fields are
    // non-blank whenever voiceEnabled is true.
    val voiceConfig = if (settings.voiceEnabled) {
        dev.sophi.companion.voice.VoiceConfig(
            whisperBinaryPath = settings.whisperBinaryPath!!,
            whisperModelPath = settings.whisperModelPath!!,
            piperPythonPath = settings.piperBinaryPath!!,
            piperVoicePath = settings.piperVoicePath!!
        )
    } else null
    companionRuntime = CompanionRuntime(
        sophiRuntime = sophiRuntime,
        sessionManager = dev.sophi.core.session.FileSessionManager(Path.of(settings.sessionsDir)),
        mcpConfigPath = Path.of(settings.mcpConfigPath),
        taskStore = dev.sophi.schedule.store.TaskStore(tasksDir.resolve("tasks.json")),
        runLog = dev.sophi.schedule.store.RunLog(tasksDir.resolve("runs.jsonl")),
        notifier = scheduleNotifier,
        notificationCenter = notificationCenter,
        voiceConfig = voiceConfig
    )
    companionRuntime.startSchedulePolling()
    return companionRuntime
}

private class BadgedPainter(
    private val base: androidx.compose.ui.graphics.painter.Painter,
    private val showBadge: Boolean
) : androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize: androidx.compose.ui.geometry.Size = base.intrinsicSize

    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        with(base) { draw(size) }
        if (showBadge) {
            drawCircle(
                color = androidx.compose.ui.graphics.Color(0xFFE53935),
                radius = size.minDimension * 0.16f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.18f)
            )
        }
    }
}

fun main() = application {
    val settingsStore = remember { SettingsStore(Path.of(System.getProperty("user.home"), ".sophi", "companion.json")) }
    // A file that exists but is unusable (e.g. written by an older build with a blank model) routes
    // to the setup screen pre-filled, rather than dead-ending on a startup error with no way to fix it.
    val storedSettings = remember { runCatching { settingsStore.load() }.getOrNull() }
    val storedProblem = remember { storedSettings?.validationError() }
    var settings by remember { mutableStateOf(storedSettings?.takeIf { storedProblem == null }) }
    var runtime by remember { mutableStateOf<CompanionRuntime?>(null) }
    var isWindowVisible by remember { mutableStateOf(false) }
    val trayState = rememberTrayState()
    val baseTrayIcon = painterResource("icons/logo.png")
    val hasUnreadNotifications = runtime?.notificationCenter?.records?.collectAsState()?.value?.any { !it.read } ?: false

    Tray(
        icon = remember(hasUnreadNotifications) { BadgedPainter(baseTrayIcon, hasUnreadNotifications) },
        state = trayState,
        tooltip = "Sophi Companion",
        onAction = { isWindowVisible = !isWindowVisible },
        menu = {
            Item("Open Sophi", onClick = { isWindowVisible = true })
            Item("Quit", onClick = ::exitApplication)
        }
    )

    if (isWindowVisible) {
        Window(
            onCloseRequest = { isWindowVisible = false },
            title = "Sophi Companion",
            state = rememberWindowState(size = DpSize(900.dp, 600.dp)),
        ) {
            window.minimumSize = java.awt.Dimension(700, 450)
            dev.sophi.companion.ui.SophiTheme {
                val currentSettings = settings
                if (currentSettings == null) {
                    dev.sophi.companion.ui.FirstRunSettingsScreen(
                        existing = storedSettings,
                        problem = storedProblem,
                        onSaved = { newSettings ->
                            settingsStore.save(newSettings)
                            settings = newSettings
                            runtime = buildRuntime(newSettings, settingsStore.resolveApiKey(newSettings))
                        }
                    )
                } else {
                    val current = runtime ?: buildRuntime(currentSettings, settingsStore.resolveApiKey(currentSettings)).also { runtime = it }
                    AppShell(current, currentSettings.pttHotkey)
                }
            }
        }
    }
}
