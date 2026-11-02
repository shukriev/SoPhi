package dev.sophi.gateway.discord

import dev.sophi.gateway.GatewayHost
import dev.sophi.gateway.GatewayIn
import dev.sophi.gateway.GatewayOut
import dev.sophi.gateway.GatewayResult
import dev.sophi.gateway.People
import dev.sophi.gateway.Resolution
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

private class FakeDiscord : DiscordPort {
    override val status = MutableStateFlow<DiscordStatus>(DiscordStatus.Offline(null))
    override val events = MutableSharedFlow<DiscordAction.Dispatch>(extraBufferCapacity = 64)
    val sent = CopyOnWriteArrayList<Pair<String, OutgoingMessage>>()
    val edits = CopyOnWriteArrayList<Pair<String, OutgoingMessage>>()
    val answers = CopyOnWriteArrayList<OutgoingMessage>()
    var started = 0
    var stopped = 0
    private val ids = AtomicInteger()
    override fun start() { started++; status.value = DiscordStatus.Connected("SoPhi", "app1") }
    override fun stop() { stopped++; status.value = DiscordStatus.Offline(null) }
    override suspend fun openDm(userId: String) = "dm-$userId"
    override suspend fun send(channelId: String, message: OutgoingMessage): String { sent += channelId to message; return "m${ids.incrementAndGet()}" }
    override suspend fun edit(channelId: String, messageId: String, message: OutgoingMessage) { edits += messageId to message }
    override suspend fun answerInteraction(id: String, token: String, message: OutgoingMessage) { answers += message }

    fun dispatch(type: String, json: String) = runBlocking { events.emit(DiscordAction.Dispatch(type, Json.parseToJsonElement(json).jsonObject)) }
}

private class FakeGatewayHost(var result: (GatewayIn) -> GatewayResult = { GatewayResult.Sent("phone-1") }) : GatewayHost {
    override val out = MutableSharedFlow<GatewayOut>(extraBufferCapacity = 64)
    val received = CopyOnWriteArrayList<GatewayIn>()
    override suspend fun receive(m: GatewayIn): GatewayResult { received += m; return result(m) }
}

private class MemoryTokens(var token: String? = "tok") : TokenStore {
    override fun get() = token
    override fun save(token: String) { this.token = token }
    override fun remove() { token = null }
}

private class Rig(
    val transcriber: (suspend (Path) -> Result<String>)? = { Result.success("what's on today") },
    val fetchFrom: Path? = null,
) {
    val dir: Path = createTempDirectory("gateway")
    val discord = FakeDiscord()
    val host = FakeGatewayHost()
    val tokens = MemoryTokens()
    val state = DiscordState(dir.resolve("phone.json"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val log: Path = dir.resolve("phone.log")
    val gateway = DiscordGateway(
        discord, state, People(dir.resolve("people.json")), tokens,
        transcribe = { transcriber }, photoText = { "Total 42" }, inbox = dir.resolve("inbox"), log = log, scope = scope,
        nowMs = { 1_000_000L }, zone = ZoneOffset.UTC,
        fetch = { _, _, to -> Files.createDirectories(to.parent); Files.copy(fetchFrom!!, to); Result.success(to) },
        newCode = { codes.removeFirstOrNull() ?: "999999" },
    )
    val codes = mutableListOf("482913", "111111", "222222")

    init { gateway.start(); gateway.bind(host) }
    fun close() { gateway.stop(); scope.cancel() }

    fun dm(content: String, author: String = "u1", extra: String = "") =
        discord.dispatch("MESSAGE_CREATE", """{"id":"msg1","channel_id":"dm1","content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), content)},"author":{"id":"$author","username":"sam"}$extra}""")

    fun pair() { runBlocking { waitFor { gateway.pairingCode.value != null } }; dm("482 913"); runBlocking { waitFor { state.pairing != null } } }

    fun click(customId: String, user: String = "u1") = discord.dispatch("INTERACTION_CREATE",
        """{"type":3,"id":"i1","token":"t1","user":{"id":"$user"},"data":{"custom_id":"$customId"},"message":{"content":"**SoPhi asks** · Chat\nSend email"}}""")
}

private suspend fun waitFor(poll: () -> Boolean) = withTimeout(5_000) { while (!poll()) delay(10) }

class DiscordGatewayTest : FunSpec({
    test("pairs with the code and only then answers") {
        val r = Rig()
        r.dm("hello")
        runBlocking { delay(100) }
        r.host.received.shouldBeEmpty()
        r.pair()
        r.state.pairing shouldBe Pairing("u1", "sam", "dm1")
        r.dm("hi")
        runBlocking { waitFor { r.host.received.isNotEmpty() } }
        (r.host.received.single() as GatewayIn.Message).let { it.text shouldBe "hi"; it.from.gatewayId shouldBe "discord:u1" }
        r.close()
    }

    test("five wrong codes make a new code") {
        val r = Rig()
        runBlocking { waitFor { r.gateway.pairingCode.value == "482913" } }
        repeat(5) { r.dm("000000") }
        runBlocking { waitFor { r.gateway.pairingCode.value == "111111" } }
        r.dm("482913")
        runBlocking { delay(100) }
        r.state.pairing shouldBe null
        r.close()
    }

    test("guild message ignored and logged") {
        val r = Rig(); r.pair()
        r.dm("hi", extra = ""","guild_id":"g1"""")
        runBlocking { waitFor { Files.exists(r.log) && Files.readString(r.log).contains("server message") } }
        r.host.received.shouldBeEmpty()
        Files.readString(r.log) shouldNotContain "hi"
        r.close()
    }

    test("other user's DM ignored") {
        val r = Rig(); r.pair()
        r.dm("hi", author = "u2")
        runBlocking { delay(100) }
        r.host.received.shouldBeEmpty()
        r.close()
    }

    test("new sends NewChat and the new-chat copy") {
        val r = Rig(); r.pair()
        r.host.result = { if (it is GatewayIn.NewChat) GatewayResult.NewChatStarted else GatewayResult.Sent("phone-1") }
        r.dm(" New ")
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        (r.host.received.single() is GatewayIn.NewChat).shouldBeTrue()
        r.discord.sent.single() shouldBe ("dm1" to OutgoingMessage(NEW_CHAT_STARTED))
        r.close()
    }

    test("voice without model gets the install text") {
        val r = Rig(transcriber = null); r.pair()
        r.dm("", extra = ""","flags":8192,"attachments":[{"url":"https://cdn.discordapp.com/v.ogg","content_type":"audio/ogg","duration_secs":4.0,"size":1000}]""")
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        r.discord.sent.single().second.content shouldBe VOICE_NEEDS_MODEL
        r.close()
    }

    test("voice over 5 minutes gets the too-long text") {
        val r = Rig(); r.pair()
        r.dm("", extra = ""","flags":8192,"attachments":[{"url":"https://cdn.discordapp.com/v.ogg","content_type":"audio/ogg","duration_secs":301.5,"size":1000}]""")
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        r.discord.sent.single().second.content shouldBe VOICE_TOO_LONG
        r.close()
    }

    test("voice is transcribed and the reply starts with what was heard") {
        val ogg = Path.of(DiscordGatewayTest::class.java.getResource("/discord/voice-3s.ogg")!!.toURI())
        val r = Rig(fetchFrom = ogg); r.pair()
        r.dm("", extra = ""","flags":8192,"attachments":[{"url":"https://cdn.discordapp.com/v.ogg","content_type":"audio/ogg","duration_secs":3.0,"size":48811}]""")
        runBlocking { waitFor { r.host.received.isNotEmpty() } }
        (r.host.received.single() as GatewayIn.Message).let { it.voice.shouldBeTrue(); it.text shouldBe dev.sophi.gateway.voiceMessage("what's on today") }
        runBlocking { r.host.out.emit(GatewayOut.Reply("phone-1", "Two meetings.", 1_000_000L)) }
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        r.discord.sent.single().second.content shouldBe "*Heard: what's on today*\nTwo meetings."
        Files.list(r.dir.resolve("inbox")).use { it.count() } shouldBe 0L   // the ogg and wav are gone
        r.close()
    }

    test("a click is answered while a voice message is still being transcribed") {
        val ogg = Path.of(DiscordGatewayTest::class.java.getResource("/discord/voice-3s.ogg")!!.toURI())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val r = Rig(transcriber = { gate.await(); Result.success("hi") }, fetchFrom = ogg); r.pair()
        r.dm("", extra = ""","flags":8192,"attachments":[{"url":"https://cdn.discordapp.com/v.ogg","content_type":"audio/ogg","duration_secs":3.0,"size":48811}]""")
        runBlocking { delay(200) }
        r.host.result = { GatewayResult.Answered(true) }
        r.click("p:permission:s1:abc:allow")
        runBlocking { waitFor { r.discord.answers.isNotEmpty() } }   // before the transcription is let go
        gate.complete(Unit)
        runBlocking { waitFor { r.host.received.any { it is GatewayIn.Message } } }
        r.close()
    }

    test("nothing piles up while the phone is off") {
        val r = Rig(); r.pair()
        r.gateway.stop()
        runBlocking {
            r.host.out.emit(GatewayOut.Notice("Reminder", "while off", 1_000_000L))
            delay(100)
        }
        r.gateway.start()
        runBlocking { delay(200) }
        r.discord.sent.shouldBeEmpty()
        r.close()
    }

    test("a card answered before it went out is never sent, and a card is sent once") {
        val r = Rig(); r.pair()
        r.discord.status.value = DiscordStatus.Connecting
        runBlocking {
            r.host.out.emit(GatewayOut.Permission("permission:s1:a", "s1", "Chat", "Send email", canAnswer = true, tainted = false, 1_000_000L))
            r.host.out.emit(GatewayOut.Resolved("permission:s1:a", Resolution.ELSEWHERE, 1_000_000L))
            r.host.out.emit(GatewayOut.Permission("permission:s1:b", "s1", "Chat", "Send it", canAnswer = true, tainted = false, 1_000_000L))
            delay(100)
        }
        r.discord.status.value = DiscordStatus.Connected("SoPhi", "app1")
        runBlocking { waitFor { r.discord.sent.isNotEmpty() }; delay(100) }
        runBlocking { r.host.out.emit(GatewayOut.Permission("permission:s1:b", "s1", "Chat", "Send it", canAnswer = true, tainted = false, 1_000_000L)); delay(150) }
        r.discord.sent.map { it.second.content } shouldBe listOf("**SoPhi asks** · Chat\nSend it")
        r.close()
    }

    test("pdf gets the take-text-voice-photos text") {
        val r = Rig(); r.pair()
        r.dm("", extra = ""","attachments":[{"url":"https://cdn.discordapp.com/a.pdf","content_type":"application/pdf","size":1000}]""")
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        r.discord.sent.single().second.content shouldBe TAKES_TEXT_VOICE_PHOTOS
        r.host.received.shouldBeEmpty()
        r.close()
    }

    test("busy photo gets the send-again text") {
        val png = createTempDirectory("png").resolve("p.png").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val r = Rig(fetchFrom = png); r.pair()
        r.host.result = { GatewayResult.Sent("phone-1", pictureDropped = true) }
        r.dm("receipt", extra = ""","attachments":[{"url":"https://cdn.discordapp.com/p.png","content_type":"image/png","size":3}]""")
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        r.discord.sent.single().second.content shouldBe BUSY_PHOTO
        (r.host.received.single() as GatewayIn.Message).let { it.photoPath shouldNotBe null; it.text shouldContain "Total 42" }
        r.close()
    }

    test("click shows the host's result") {
        val r = Rig(); r.pair()
        listOf(
            GatewayResult.AlreadyAnswered to "Answered on the Mac",
            GatewayResult.NotAllowed to "Approve this on your Mac.",
            GatewayResult.Answered(true) to "Allowed from your phone · 00:16",
        ).forEach { (result, text) ->
            r.host.result = { result }
            r.discord.answers.clear()
            r.click("p:permission:s1:allow")
            runBlocking { waitFor { r.discord.answers.isNotEmpty() } }
            r.discord.answers.single().content shouldEndWith "\n$text"
            r.discord.answers.single().components.shouldBeEmpty()
        }
        (r.host.received.last() as GatewayIn.AnswerPermission).let { it.key shouldBe "permission:s1"; it.allow.shouldBeTrue() }
        r.click("p:permission:s1:allow", user = "u2")
        runBlocking { delay(100) }
        r.host.received.size shouldBe 3
        r.close()
    }

    test("plan buttons answer the plan, and the card says so") {
        val r = Rig(); r.pair()
        runBlocking { r.host.out.emit(GatewayOut.Plan("plan:p1", "s1", "Chat", "offboard jane", listOf("okta deactivate_user"), canAnswer = true, 1_000_000L)) }
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        r.discord.sent.single().second.components.single().components.map { it.customId } shouldBe listOf("pl:plan:p1:approve", "pl:plan:p1:cancel")
        r.host.result = { GatewayResult.Answered(true) }
        r.click("pl:plan:p1:approve")
        runBlocking { waitFor { r.discord.answers.isNotEmpty() } }
        (r.host.received.single() as GatewayIn.AnswerPlan).let { it.key shouldBe "plan:p1"; it.approve.shouldBeTrue() }
        r.discord.answers.single().content shouldEndWith "\nApproved from your phone · 00:16"
        r.close()
    }

    test("Mac answer edits the phone card to Answered on the Mac") {
        val r = Rig(); r.pair()
        runBlocking { r.host.out.emit(GatewayOut.Permission("permission:s1", "s1", "Chat", "Send email", canAnswer = true, tainted = false, 1_000_000L)) }
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        runBlocking { r.host.out.emit(GatewayOut.Resolved("permission:s1", Resolution.ELSEWHERE, 1_000_000L)) }
        runBlocking { waitFor { r.discord.edits.isNotEmpty() } }
        r.discord.edits.single().let { (id, m) -> id shouldBe "m1"; m.content shouldEndWith "\nAnswered on the Mac"; m.components.shouldBeEmpty() }
        r.close()
    }

    test("rebind keeps the connection and ends old cards") {
        val r = Rig(); r.pair()
        runBlocking { r.host.out.emit(GatewayOut.Permission("permission:s1", "s1", "Chat", "Send email", canAnswer = true, tainted = false, 1_000_000L)) }
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        val next = FakeGatewayHost()
        r.gateway.bind(next)
        runBlocking { waitFor { r.discord.edits.isNotEmpty() } }
        r.discord.edits.single().second.content shouldEndWith "\n$ENDED_ON_MAC"
        r.discord.stopped shouldBe 0
        runBlocking { waitFor { next.out.subscriptionCount.value > 0 } }
        runBlocking { next.out.emit(GatewayOut.Permission("permission:s1", "s1", "Chat", "Send email", canAnswer = true, tainted = false, 1_000_000L)) }
        runBlocking { waitFor { r.discord.sent.size == 2 } }
        r.dm("still there?")
        runBlocking { waitFor { next.received.isNotEmpty() } }
        r.host.received.shouldBeEmpty()
        r.close()
    }

    test("a message while no host is bound waits for the next one") {
        val r = Rig(); r.pair()
        r.gateway.bind(null)
        r.dm("during the rebuild")
        runBlocking { delay(100) }
        val next = FakeGatewayHost()
        r.gateway.bind(next)
        runBlocking { waitFor { next.received.isNotEmpty() } }
        (next.received.single() as GatewayIn.Message).text shouldBe "during the rebuild"
        r.close()
    }

    test("queued while offline, sent on connect") {
        val r = Rig(); r.pair()
        r.discord.status.value = DiscordStatus.Connecting
        runBlocking {
            r.host.out.emit(GatewayOut.Notice("Reminder", "one", 1_000_000L))
            r.host.out.emit(GatewayOut.Notice("Reminder", "two", 1_000_000L))
            delay(100)
        }
        r.discord.sent.shouldBeEmpty()
        r.discord.status.value = DiscordStatus.Connected("SoPhi", "app1")
        runBlocking { waitFor { r.discord.sent.size == 2 } }
        r.discord.sent.map { it.second.content } shouldBe listOf("**Reminder** · one", "**Reminder** · two")
        r.close()
    }

    test("unpair") {
        val r = Rig(); r.pair()
        r.gateway.unpair()
        r.tokens.token shouldBe null
        (r.discord.stopped > 0).shouldBeTrue()
        r.state.pairing shouldBe null
        DiscordState(r.dir.resolve("phone.json")).pairing shouldBe null
        r.gateway.start()
        r.dm("hi")
        runBlocking { delay(100) }
        r.host.received.shouldBeEmpty()
        r.close()
    }

    test("token never written to settings or phone.json") {
        val r = Rig()
        r.gateway.saveToken("  secret-token  ")
        r.tokens.token shouldBe "secret-token"
        r.pair()
        Files.readString(r.dir.resolve("phone.json")).contains("secret-token").shouldBeFalse()
        r.close()
    }
})
