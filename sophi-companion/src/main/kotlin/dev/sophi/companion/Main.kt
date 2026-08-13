package dev.sophi.companion

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
import dev.sophi.schedule.notify.CrossPlatformNotifier
import dev.sophi.schedule.notify.NativeNotifications
import java.nio.file.Path

private fun buildRuntime(settings: CompanionSettings, apiKey: String?): CompanionRuntime {
    settings.validationError()?.let { error("Invalid ~/.sophi/companion.json: $it") }
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
            notify = { t, b -> NativeNotifications.send(t, b) },
            // Routed per session via SessionIdContext (see CompanionRuntime.sendMessage):
            // awaitConfirmation sets that session's state to NeedsConfirmation and suspends
            // until the matching Chat tab's Approve/Deny calls respondToConfirmation.
            onConfirmationNeeded = { sessionId, requests -> companionRuntime.awaitConfirmation(sessionId, requests) }
        ))
    }
    val tasksDir = Path.of(System.getProperty("user.home"), ".sophi", "companion")
    companionRuntime = CompanionRuntime(
        sophiRuntime = sophiRuntime,
        sessionManager = dev.sophi.core.session.FileSessionManager(Path.of(settings.sessionsDir)),
        mcpConfigPath = Path.of(settings.mcpConfigPath),
        taskStore = dev.sophi.schedule.store.TaskStore(tasksDir.resolve("tasks.json")),
        runLog = dev.sophi.schedule.store.RunLog(tasksDir.resolve("runs.jsonl")),
        notifier = CrossPlatformNotifier()
    )
    companionRuntime.startSchedulePolling()
    return companionRuntime
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

    Tray(
        icon = painterResource("icons/logo.png"),
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
                    AppShell(current)
                }
            }
        }
    }
}
