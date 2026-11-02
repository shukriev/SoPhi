package dev.sophi.gateway.discord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

sealed interface DiscordStatus {
    /** [reason] is set when Discord refused SoPhi (a bad token); null when simply not started. */
    data class Offline(val reason: String?) : DiscordStatus
    data object Connecting : DiscordStatus
    data class Connected(val botName: String, val applicationId: String) : DiscordStatus
}

/** What a gateway needs from Discord, so tests can stand in for it. */
interface DiscordPort {
    val status: StateFlow<DiscordStatus>
    val events: SharedFlow<DiscordAction.Dispatch>
    fun start()
    fun stop()
    suspend fun openDm(userId: String): String
    /** The new message's id. */
    suspend fun send(channelId: String, message: OutgoingMessage): String
    suspend fun edit(channelId: String, messageId: String, message: OutgoingMessage)
    /** Edits the clicked message in place (callback type 7); Discord wants it within 3 seconds. */
    suspend fun answerInteraction(id: String, token: String, message: OutgoingMessage)
}

/** Discord's invite page for the bot, asking for no permissions: it only needs to share a server with the user. */
fun inviteUrl(applicationId: String) = "https://discord.com/oauth2/authorize?client_id=$applicationId&scope=bot&permissions=0"

private const val GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json"
private const val API = "https://discord.com/api/v10"
private const val MAX_BACKOFF_MS = 60_000L

/** Discord's Gateway and REST API over java.net.http: an outgoing connection only, nothing listens here. */
class DiscordClient(
    private val tokenProvider: () -> String?,
    private val scope: CoroutineScope,
    private val http: HttpClient = HttpClient.newHttpClient(),
) : DiscordPort {
    private val _status = MutableStateFlow<DiscordStatus>(DiscordStatus.Offline(null))
    override val status: StateFlow<DiscordStatus> = _status
    private val _events = MutableSharedFlow<DiscordAction.Dispatch>(extraBufferCapacity = 256)
    override val events: SharedFlow<DiscordAction.Dispatch> = _events
    private var job: Job? = null
    /** Shown again on RESUMED, which doesn't repeat READY's bot name. */
    private var lastConnected: DiscordStatus.Connected? = null

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
        _status.value = DiscordStatus.Offline(null)
    }

    private suspend fun run() {
        var backoff = 1_000L
        var session: DiscordSession? = null
        while (coroutineContext.isActive) {
            val token = tokenProvider() ?: run { _status.value = DiscordStatus.Offline(null); return }
            val s = session?.takeIf { it.sessionId != null } ?: DiscordSession(token)
            session = s
            _status.value = DiscordStatus.Connecting
            val (end, wasConnected) = try {
                connectOnce(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiscordAction.Reconnect(resume = s.sessionId != null) to false
            }
            if (end is DiscordAction.Fatal) { _status.value = DiscordStatus.Offline(end.reason); return }
            if (wasConnected) backoff = 1_000L
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** One socket, until it ends: returns how it ended and whether it got as far as READY/RESUMED. */
    private suspend fun connectOnce(s: DiscordSession): Pair<DiscordAction, Boolean> = coroutineScope {
        val url = s.resumeUrl?.takeIf { s.sessionId != null }?.let { "$it/?v=10&encoding=json" } ?: GATEWAY
        val incoming = Channel<String>(Channel.UNLIMITED)
        var closeCode = -1
        val listener = object : WebSocket.Listener {
            private val text = StringBuilder()
            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (last) { incoming.trySend(text.toString()); text.setLength(0) }
                ws.request(1)
                return null
            }
            override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                closeCode = statusCode
                incoming.close()
                return null
            }
            override fun onError(ws: WebSocket, error: Throwable) { incoming.close() }
        }
        val ws = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20)).buildAsync(URI(url), listener).await()
        val sendLock = Mutex()
        suspend fun send(json: String) = sendLock.withLock { ws.sendText(json, true).await() }
        var heartbeat: Job? = null
        var acked = true
        var connected = false
        try {
            for (message in incoming) for (action in s.onPayload(message)) when (action) {
                is DiscordAction.Send -> send(action.json)
                is DiscordAction.StartHeartbeat -> {
                    heartbeat?.cancel()
                    acked = true
                    heartbeat = launch {
                        delay((action.intervalMs * Random.nextDouble()).toLong())
                        while (true) {
                            // No ACK since the last beat: the connection is dead even if the socket isn't closed.
                            if (!acked) { incoming.close(); ws.abort(); return@launch }
                            acked = false
                            send(s.heartbeat())
                            delay(action.intervalMs)
                        }
                    }
                }
                DiscordAction.HeartbeatAck -> acked = true
                is DiscordAction.Dispatch -> {
                    when (action.type) {
                        "READY" -> {
                            _status.value = DiscordStatus.Connected(
                                action.data.str("user", "username").orEmpty(), action.data.str("application", "id").orEmpty())
                            connected = true
                        }
                        "RESUMED" -> {
                            lastConnected?.let { _status.value = it }
                            connected = true
                        }
                    }
                    (_status.value as? DiscordStatus.Connected)?.let { lastConnected = it }
                    _events.emit(action)
                }
                is DiscordAction.Reconnect -> {
                    // A non-1000 close keeps the session resumable.
                    runCatching { ws.sendClose(if (action.resume) 4000 else WebSocket.NORMAL_CLOSURE, "reconnect").await() }
                    return@coroutineScope action to connected
                }
                is DiscordAction.Fatal -> return@coroutineScope action to connected
            }
            s.onClosed(closeCode) to connected
        } finally {
            heartbeat?.cancel()
            ws.abort()
        }
    }


    override suspend fun openDm(userId: String): String =
        rest("POST", "/users/@me/channels", buildJsonObject { put("recipient_id", userId) }.toString()).id()

    override suspend fun send(channelId: String, message: OutgoingMessage): String =
        rest("POST", "/channels/$channelId/messages", discordJson.encodeToString(OutgoingMessage.serializer(), message)).id()

    override suspend fun edit(channelId: String, messageId: String, message: OutgoingMessage) {
        rest("PATCH", "/channels/$channelId/messages/$messageId", discordJson.encodeToString(OutgoingMessage.serializer(), message))
    }

    override suspend fun answerInteraction(id: String, token: String, message: OutgoingMessage) {
        val body = buildJsonObject { put("type", 7); put("data", discordJson.encodeToJsonElement(OutgoingMessage.serializer(), message)) }
        rest("POST", "/interactions/$id/$token/callback", body.toString())
    }

    /** One REST call; a 429 waits Discord's `retry_after` once and tries again. */
    private suspend fun rest(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        val token = tokenProvider() ?: throw IOException("no Discord token")
        repeat(2) { attempt ->
            val request = HttpRequest.newBuilder(URI(API + path))
                .header("Authorization", "Bot $token")
                .header("Content-Type", "application/json")
                .header("User-Agent", "DiscordBot (https://sophiloop.com, 1)")
                .timeout(Duration.ofSeconds(30))
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 429 && attempt == 0) {
                val wait = runCatching { discordJson.parseToJsonElement(response.body()).jsonObject["retry_after"]?.jsonPrimitive?.doubleOrNull }
                    .getOrNull() ?: 1.0
                delay((wait * 1000).toLong())
                return@repeat
            }
            if (response.statusCode() !in 200..299) throw IOException("Discord answered ${response.statusCode()}: ${response.body().take(200)}")
            return@withContext response.body()
        }
        throw IOException("Discord kept rate-limiting SoPhi")
    }
}

private fun String.id(): String = discordJson.parseToJsonElement(this).jsonObject["id"]!!.jsonPrimitive.content

private fun JsonObject.str(obj: String, key: String): String? = (this[obj] as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull
