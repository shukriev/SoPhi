package dev.sophi.gateway.discord

import dev.sophi.gateway.GatewayHost
import dev.sophi.gateway.GatewayAddress
import dev.sophi.gateway.GatewayIn
import dev.sophi.gateway.GatewayOut
import dev.sophi.gateway.GatewayResult
import dev.sophi.gateway.People
import dev.sophi.gateway.Resolution
import dev.sophi.gateway.photoMessage
import dev.sophi.gateway.voiceMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

const val VOICE_NEEDS_MODEL = "Voice messages need SoPhi's voice model on your computer. Install it in Settings › Voice."
const val VOICE_TOO_LONG = "That voice message is longer than 5 minutes. Send a shorter one, or type it."
const val TAKES_TEXT_VOICE_PHOTOS = "SoPhi can take text, voice messages and photos for now."
const val BUSY_PHOTO = "I was busy, so I read the text in your photo but didn't see the picture. Send it again after my reply if I should look at it."
const val NEW_CHAT_STARTED = "Started a new Phone chat. The last one is still on your Mac."
const val CANT_READ_PHOTOS = "(This computer can't read text in photos yet.)"

private const val VOICE_FLAG = 1 shl 13
private const val MAX_VOICE_SECONDS = 300.0
private const val MAX_VOICE_BYTES = 25L * 1024 * 1024
private const val MAX_PHOTO_BYTES = 10L * 1024 * 1024
private val PHOTO_TYPES = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif")
private const val CODE_LIFETIME_MS = 10 * 60_000L
private const val WRONG_TRIES = 5
private const val GATEWAY_WAIT_MS = 60_000L

/**
 * SoPhi on Discord: DMs from the one paired user go through a [GatewayHost], and what the host sends
 * comes back as DMs, with buttons for permissions, questions and plans. Knows nothing of the host: it is
 * handed how to [transcribe] a WAV (null without a voice model), read [photoText] (null when it
 * can't), and keep the token ([tokens]). It outlives the host it is [bind]-ed to.
 */
class DiscordGateway(
    private val client: DiscordPort,
    private val state: DiscordState,
    private val people: People,
    private val tokens: TokenStore,
    private val transcribe: () -> (suspend (Path) -> Result<String>)?,
    private val photoText: (Path) -> String?,
    /** Downloads land here; the host moves a photo to its chat. */
    private val inbox: Path,
    private val log: Path,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val fetch: suspend (url: String, maxBytes: Long, to: Path) -> Result<Path> =
        HttpClient.newHttpClient().let { http -> { url, max, to -> download(url, max, to, http) } },
    private val newCode: () -> String = { "%06d".format(SecureRandom().nextInt(1_000_000)) },
) {
    private val host = MutableStateFlow<GatewayHost?>(null)
    private var hostJob: Job? = null
    private val jobs = mutableListOf<Job>()

    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode
    private val _joinedServer = MutableStateFlow<String?>(null)
    val joinedServer: StateFlow<String?> = _joinedServer
    val status: StateFlow<DiscordStatus> = client.status
    private val _fatal = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Discord refused SoPhi (a token that stopped working): the host tells the user. */
    val fatal: SharedFlow<String> = _fatal
    private val _paired = MutableStateFlow(state.pairing)
    val paired: StateFlow<Pairing?> = _paired

    private var wrongTries = 0
    private var codeIssuedMs = 0L

    /** Cards on the phone still showing buttons: key → the message, so it can be edited later. */
    private class Card(val messageId: String, val channelId: String, val content: String, val choices: List<String>)
    private val cards = ConcurrentHashMap<String, Card>()
    /** What a voice message was heard as, put before the reply to it. */
    private val heard = ConcurrentHashMap<String, String>()
    /** [channelId] is fixed when queued; null: [personId] has no door, so it is dropped, never sent to the owner instead. */
    private class Outgoing(val message: OutgoingMessage, val key: String?, val choices: List<String>, val channelId: String?, val personId: String? = null)
    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
    /** Cards waiting in [outbox], and those of them answered before they went out. */
    private val queuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val resolvedEarly = ConcurrentHashMap.newKeySet<String>()
    /** DMs in arrival order, one at a time; clicks don't wait behind them. */
    private val inbound = Channel<DiscordAction.Dispatch>(Channel.UNLIMITED)
    /** Off: nothing SoPhi says is kept for later; turning the phone on doesn't flood it. */
    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (jobs.isNotEmpty()) return
        running = true
        // A click must be answered within Discord's 3 seconds, even while a voice message is being transcribed.
        jobs += scope.launch {
            client.events.collect { d ->
                if (d.type == "INTERACTION_CREATE") launch { runCatching { onInteraction(d.data) }.onFailure { logLine("error ${it.message}") } }
                else inbound.send(d)
            }
        }
        jobs += scope.launch { for (d in inbound) runCatching { onDispatch(d) }.onFailure { logLine("error ${it.message}") } }
        // What is still waiting (a card from before the phone was off) is offered again; one already on the phone isn't repeated.
        host.value?.let { d -> hostJob?.cancel(); hostJob = scope.launch { d.out.collect { onGatewayOut(it) } } }
        jobs += scope.launch {
            client.status.collect { s -> if (s is DiscordStatus.Offline && s.reason != null) _fatal.emit(s.reason) }
        }
        jobs += scope.launch { sendOutbox() }
        jobs += scope.launch { while (true) { refreshCode(); delay(1_000) } }
        client.start()
    }

    @Synchronized
    fun stop() {
        running = false
        client.stop()
        jobs.forEach { it.cancel() }
        jobs.clear()
        while (outbox.tryReceive().isSuccess) Unit
        queuedKeys.clear(); resolvedEarly.clear()
    }

    /** A new host after a runtime rebuild (or null when it closes): the old host's cards ended with it. */
    fun bind(next: GatewayHost?) {
        hostJob?.cancel()
        val ended = cards.toMap().also { cards.clear() }
        ended.values.forEach { card ->
            scope.launch { runCatching { client.edit(card.channelId, card.messageId, OutgoingMessage(card.content + "\n" + ENDED_ON_MAC)) } }
        }
        host.value = next
        hostJob = next?.let { d -> scope.launch { d.out.collect { onGatewayOut(it) } } }
    }

    fun hasToken(): Boolean = tokens.get() != null

    fun saveToken(token: String) {
        tokens.save(token.trim())
        client.stop()
        client.start()
    }

    @Synchronized
    fun unpair() {
        stop()
        tokens.remove()
        state.clear()
        _paired.value = null
        _joinedServer.value = null
        cards.clear()
    }

    private fun refreshCode() {
        if (state.pairing != null) { _pairingCode.value = null; return }
        if (_pairingCode.value == null || nowMs() - codeIssuedMs > CODE_LIFETIME_MS || wrongTries >= WRONG_TRIES) {
            _pairingCode.value = newCode()
            codeIssuedMs = nowMs()
            wrongTries = 0
        }
    }

    private suspend fun onDispatch(d: DiscordAction.Dispatch) {
        when (d.type) {
            "GUILD_CREATE" -> _joinedServer.value = d.data.str("name")
            "MESSAGE_CREATE" -> onMessage(d.data)
        }
    }

    private suspend fun onMessage(m: JsonObject) {
        val author = m["author"] as? JsonObject ?: return
        val authorId = author.str("id") ?: return
        if ((author["bot"] as? JsonPrimitive)?.booleanOrNull == true) return
        if (m["guild_id"] != null && m["guild_id"] !is kotlinx.serialization.json.JsonNull) { logLine("ignored a server message from $authorId"); return }
        val channelId = m.str("channel_id") ?: return
        val content = m.str("content").orEmpty()
        val pairing = state.pairing
        if (pairing == null) {
            refreshCode()
            if (content.replace(" ", "").trim() == _pairingCode.value) {
                Pairing(authorId, author.str("username").orEmpty(), channelId).let { state.pair(it); _paired.value = it }
                _pairingCode.value = null
            } else {
                wrongTries++
                logLine("ignored a DM from $authorId while unpaired")
                refreshCode()
            }
            return
        }
        if (authorId != pairing.userId) { logLine("ignored a DM from $authorId"); return }
        val from = GatewayAddress(people.owner().id, "discord:$authorId")
        val attachments = (m["attachments"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val messageId = m.str("id") ?: "msg"
        when {
            attachments.isEmpty() && content.trim().equals("new", ignoreCase = true) ->
                if (receive(GatewayIn.NewChat(from)) == GatewayResult.NewChatStarted) say(NEW_CHAT_STARTED)
            attachments.isEmpty() -> receive(GatewayIn.Message(from, content))
            ((m["flags"] as? JsonPrimitive)?.intOrNull ?: 0) and VOICE_FLAG != 0 -> onVoice(from, attachments.first(), messageId)
            attachments.first().str("content_type")?.substringBefore(';') in PHOTO_TYPES -> onPhoto(from, attachments.first(), content, messageId)
            else -> say(TAKES_TEXT_VOICE_PHOTOS)
        }
    }

    private suspend fun onVoice(from: GatewayAddress, a: JsonObject, messageId: String) {
        val transcriber = transcribe() ?: return say(VOICE_NEEDS_MODEL)
        val seconds = (a["duration_secs"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val size = (a["size"] as? JsonPrimitive)?.longOrNull ?: 0L
        if (seconds > MAX_VOICE_SECONDS || size > MAX_VOICE_BYTES) return say(VOICE_TOO_LONG)
        val ogg = inbox.resolve("$messageId.ogg")
        val wav = inbox.resolve("$messageId.wav")
        try {
            val text = fetch(a.str("url").orEmpty(), MAX_VOICE_BYTES, ogg)
                .mapCatching { oggOpusToWav16k(it, wav).getOrThrow(); wav }
                .mapCatching { transcriber(it).getOrThrow() }
                .getOrElse { logLine("voice message failed: ${it.message}"); return say("I couldn't hear that voice message. Try again, or type it.") }
            if (text.isBlank()) return say("I couldn't hear that voice message. Try again, or type it.")
            val result = receive(GatewayIn.Message(from, voiceMessage(text), voice = true))
            if (result is GatewayResult.Sent) heard[result.sessionId] = text
        } finally {
            Files.deleteIfExists(ogg); Files.deleteIfExists(wav)
        }
    }

    private suspend fun onPhoto(from: GatewayAddress, a: JsonObject, caption: String, messageId: String) {
        val ext = PHOTO_TYPES.getValue(a.str("content_type")!!.substringBefore(';'))
        val size = (a["size"] as? JsonPrimitive)?.longOrNull ?: 0L
        if (size > MAX_PHOTO_BYTES) return say(TAKES_TEXT_VOICE_PHOTOS)
        val photo = fetch(a.str("url").orEmpty(), MAX_PHOTO_BYTES, inbox.resolve("$messageId.$ext"))
            .getOrElse { logLine("photo failed: ${it.message}"); return say(TAKES_TEXT_VOICE_PHOTOS) }
        val text = photoText(photo) ?: CANT_READ_PHOTOS
        val result = receive(GatewayIn.Message(from, photoMessage(caption, text), photoPath = photo.toString()))
        if ((result as? GatewayResult.Sent)?.pictureDropped == true) say(BUSY_PHOTO)
    }

    private suspend fun onInteraction(i: JsonObject) {
        if ((i["type"] as? JsonPrimitive)?.intOrNull != 3) return
        val id = i.str("id") ?: return
        val token = i.str("token") ?: return
        val user = (i["user"] as? JsonObject) ?: ((i["member"] as? JsonObject)?.get("user") as? JsonObject)
        val message = i["message"] as? JsonObject
        val original = message?.str("content").orEmpty()
        val pairing = state.pairing
        if (pairing == null || user?.str("id") != pairing.userId) {
            val unchanged = message?.let { runCatching { discordJson.decodeFromJsonElement(OutgoingMessage.serializer(), it) }.getOrNull() }
            return client.answerInteraction(id, token, unchanged ?: OutgoingMessage(original))
        }
        val customId = ((i["data"] as? JsonObject)?.str("custom_id")).orEmpty()
        val kind = customId.substringBefore(':')
        val key = customId.substringAfter(':').substringBeforeLast(':')
        val last = customId.substringAfterLast(':')
        val current = host.value ?: return client.answerInteraction(id, token, OutgoingMessage("$original\n$ENDED_ON_MAC"))
        val from = GatewayAddress(people.owner().id, "discord:${pairing.userId}")
        val card = cards.remove(key)
        val (result, label) = when (kind) {
            "p" -> current.receive(GatewayIn.AnswerPermission(from, key, allow = last == "allow")) to null
            "pl" -> current.receive(GatewayIn.AnswerPlan(from, key, approve = last == "approve")) to null
            "q" -> {
                val index = last.toIntOrNull()
                current.receive(GatewayIn.AnswerQuestion(from, key, index, null)) to
                    index?.let { i -> card?.choices?.getOrNull(i)?.let { "${'A' + i}  $it" } }
            }
            else -> return client.answerInteraction(id, token, OutgoingMessage(original))
        }
        if (result == GatewayResult.NotAllowed && card != null) cards[key] = card   // still waiting on the Mac
        client.answerInteraction(id, token, OutgoingMessage("$original\n" + clickText(result, label, nowMs(), zone, plan = kind == "pl")))
    }

    private suspend fun receive(m: GatewayIn): GatewayResult? {
        val d = host.value ?: withTimeoutOrNull(GATEWAY_WAIT_MS) { host.filterNotNull().first() }
        return d?.receive(m) ?: run { logLine("dropped a message: SoPhi wasn't running"); null }
    }

    private suspend fun onGatewayOut(out: GatewayOut) {
        if (!running) return
        when (out) {
            is GatewayOut.Resolved -> {
                if (out.key in queuedKeys) resolvedEarly += out.key
                val card = cards.remove(out.key) ?: return
                if (out.how != Resolution.ELSEWHERE) return
                runCatching { client.edit(card.channelId, card.messageId, OutgoingMessage(card.content + "\nAnswered on the Mac")) }
            }
            is GatewayOut.Reply -> {
                val prefix = heard.remove(out.sessionId)?.let { "*Heard: $it*\n" }.orEmpty()
                val channel = channelFor(out.personId)
                render(out.copy(text = prefix + out.text), nowMs(), zone).forEach { outbox.send(Outgoing(it, null, emptyList(), channel, out.personId)) }
            }
            is GatewayOut.Permission -> if (fresh(out.key)) render(out, nowMs(), zone).forEach { outbox.send(Outgoing(it, out.key, emptyList(), channelFor(null))) }
            is GatewayOut.Plan -> if (fresh(out.key)) render(out, nowMs(), zone).forEach { outbox.send(Outgoing(it, out.key, emptyList(), channelFor(null))) }
            is GatewayOut.Question -> if (fresh(out.key)) {
                val channel = channelFor(out.personId)
                render(out, nowMs(), zone).forEach { outbox.send(Outgoing(it, out.key, out.choices, channel, out.personId)) }
            }
            is GatewayOut.Notice -> {
                val channel = channelFor(out.personId)
                render(out, nowMs(), zone).forEach { outbox.send(Outgoing(it, null, emptyList(), channel, out.personId)) }
            }
        }
    }

    /** A card not already on the phone or on its way there; claims it. */
    private fun fresh(key: String): Boolean = !cards.containsKey(key) && queuedKeys.add(key)

    /** The owner's DM for null or the owner's id, else that member's; null when they have no door. */
    private fun channelFor(personId: String?): String? =
        if (personId == null || personId == people.owner().id) state.pairing?.dmChannelId
        else state.members.firstOrNull { it.personId == personId }?.dmChannelId

    private suspend fun say(text: String, channelId: String? = state.pairing?.dmChannelId) =
        outbox.send(Outgoing(OutgoingMessage(text), null, emptyList(), channelId))

    /** In order, and only while connected: what SoPhi sends while Discord is away waits for it. */
    private suspend fun sendOutbox() {
        for (o in outbox) {
            val channel = o.channelId
            if (channel == null) {
                o.personId?.let { logLine("no door for $it") }
                o.key?.let { queuedKeys.remove(it); resolvedEarly.remove(it) }
                continue
            }
            client.status.first { it is DiscordStatus.Connected }
            if (o.key != null && resolvedEarly.remove(o.key)) { queuedKeys.remove(o.key); continue }
            val id = runCatching { client.send(channel, o.message) }.getOrElse { logLine("send failed: ${it.message}"); o.key?.let(queuedKeys::remove); continue }
            if (o.key != null) { cards[o.key] = Card(id, channel, o.message.content, o.choices); queuedKeys.remove(o.key) }
        }
    }

    private fun logLine(text: String) {
        runCatching {
            log.parent?.let { Files.createDirectories(it) }
            Files.writeString(log, "${Instant.ofEpochMilli(nowMs())} $text\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
