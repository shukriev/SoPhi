package dev.sophi.hub

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-only (127.0.0.1) hub embedded by sophi-companion. CLI sessions connect to /hub/cli,
 * announce themselves with a [HubEvent.SessionRegistered] as their first frame, and stream
 * further [HubEvent]s; the companion process reads [events] and calls [sendCommand] directly
 * in-process (see CompanionRuntime) — there is no companion-side WebSocket hop.
 */
class HubServer(private val port: Int) {
    // encodeDefaults = true — see HubClient's identical Json config for why (HubEvent.timestamp
    // is a defaulted field; without this, its value can silently change across the wire).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cliSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private val _events = MutableSharedFlow<HubEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<HubEvent> = _events.asSharedFlow()
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/hub/cli") {
                    var registeredId: String? = null
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val event = runCatching {
                                json.decodeFromString(HubEvent.serializer(), frame.readText())
                            }.getOrNull() ?: continue
                            if (event is HubEvent.SessionRegistered) {
                                registeredId = event.sessionId
                                cliSessions[event.sessionId] = this
                            }
                            _events.emit(event)
                        }
                    } finally {
                        registeredId?.let { id ->
                            cliSessions.remove(id)
                            _events.emit(HubEvent.SessionClosed(id))
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 500)
    }

    /** No-op, does not throw, if [command]'s sessionId has no connected CLI client. */
    suspend fun sendCommand(command: HubCommand) {
        val session = cliSessions[command.sessionId] ?: return
        runCatching { session.send(Frame.Text(json.encodeToString(HubCommand.serializer(), command))) }
    }
}
