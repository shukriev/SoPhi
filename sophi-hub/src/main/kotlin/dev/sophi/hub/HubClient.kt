package dev.sophi.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * WebSocket client used by sophi-cli to register a running session with a HubServer and stream
 * TurnEvents to it. Every operation is best-effort: if the hub isn't reachable, [connect]
 * returns false and [publish] silently no-ops — a CLI session must behave identically with or
 * without a companion running.
 */
class HubClient(private val port: Int, private val sessionId: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(CIO) { install(WebSockets) }
    private var session: DefaultClientWebSocketSession? = null
    private var receiveJob: Job? = null
    private val _commands = MutableSharedFlow<HubCommand>(replay = 0, extraBufferCapacity = 256)
    val commands: SharedFlow<HubCommand> = _commands.asSharedFlow()

    /** Attempts one connection; returns false (never throws) if the hub isn't reachable. */
    suspend fun connect(scope: CoroutineScope): Boolean = runCatching {
        val ws = httpClient.webSocketSession(host = "127.0.0.1", port = port, path = "/hub/cli")
        session = ws
        receiveJob = scope.launch {
            runCatching {
                for (frame in ws.incoming) {
                    if (frame !is Frame.Text) continue
                    runCatching { json.decodeFromString(HubCommand.serializer(), frame.readText()) }
                        .onSuccess { _commands.emit(it) }
                }
            }
            session = null
        }
        true
    }.getOrElse { false }

    suspend fun publish(event: HubEvent) {
        val current = session ?: return
        runCatching { current.send(Frame.Text(json.encodeToString(HubEvent.serializer(), event))) }
    }

    suspend fun close() {
        runCatching { session?.close() }
        receiveJob?.cancel()
        httpClient.close()
    }
}
