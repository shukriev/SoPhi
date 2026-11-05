package dev.sophi.gateway.discord

import dev.sophi.gateway.GatewayAddress
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
import kotlinx.coroutines.flow.first
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
    /** channelId, messageId, message */
    val edits = CopyOnWriteArrayList<Triple<String, String, OutgoingMessage>>()
    val answers = CopyOnWriteArrayList<OutgoingMessage>()
    var started = 0
    var stopped = 0
    private val ids = AtomicInteger()
    override fun start() { started++; status.value = DiscordStatus.Connected("SoPhi", "app1") }
    override fun stop() { stopped++; status.value = DiscordStatus.Offline(null) }
    override suspend fun openDm(userId: String) = "dm-$userId"
    override suspend fun send(channelId: String, message: OutgoingMessage): String { sent += channelId to message; return "m${ids.incrementAndGet()}" }
    override suspend fun edit(channelId: String, messageId: String, message: OutgoingMessage) { edits += Triple(channelId, messageId, message) }
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
    val people = People(dir.resolve("people.json"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val log: Path = dir.resolve("phone.log")
    val gateway = DiscordGateway(
        discord, state, people, tokens,
        transcribe = { transcriber }, photoText = { "Total 42" }, inbox = dir.resolve("inbox"), log = log, scope = scope,
        nowMs = { now }, zone = ZoneOffset.UTC,
        fetch = { _, _, to -> Files.createDirectories(to.parent); Files.copy(fetchFrom!!, to); Result.success(to) },
        newCode = { codes.removeFirstOrNull() ?: "999999" },
    )
    @Volatile var now = 1_000_000L
    val codes = mutableListOf("482913", "111111", "222222")

    init {
        gateway.start(); gateway.bind(host)
        // Both flows drop what is emitted before anyone collects: wait until the gateway's collectors subscribed (CI flake).
        runBlocking { withTimeout(5_000) { discord.events.subscriptionCount.first { it > 0 }; host.out.subscriptionCount.first { it > 0 } } }
    }
    fun close() { gateway.stop(); scope.cancel() }
    fun logHas(text: String) = Files.exists(log) && Files.readString(log).contains(text)

    fun dm(content: String, author: String = "u1", extra: String = "", channel: String = "dm1") =
        discord.dispatch("MESSAGE_CREATE", """{"id":"msg1","channel_id":"$channel","content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), content)},"author":{"id":"$author","username":"sam"}$extra}""")

    fun pair() { runBlocking { waitFor { gateway.pairingCode.value != null } }; dm("482 913"); runBlocking { waitFor { state.pairing != null } } }

    /** Owner paired, then Alex pairs from u2 on dm2 with the member code. */
    fun pairAlex(): dev.sophi.gateway.Person {
        pair()
        val alex = people.add("Alex")
        dm(gateway.memberCode(alex.id), author = "u2", channel = "dm2")
        runBlocking { waitFor { state.members.isNotEmpty() } }
        return alex
    }

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
        r.discord.edits.single().let { (_, id, m) -> id shouldBe "m1"; m.content shouldEndWith "\nAnswered on the Mac"; m.components.shouldBeEmpty() }
        r.close()
    }

    test("rebind keeps the connection and ends old cards") {
        val r = Rig(); r.pair()
        runBlocking { r.host.out.emit(GatewayOut.Permission("permission:s1", "s1", "Chat", "Send email", canAnswer = true, tainted = false, 1_000_000L)) }
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        val next = FakeGatewayHost()
        r.gateway.bind(next)
        runBlocking { waitFor { r.discord.edits.isNotEmpty() } }
        r.discord.edits.single().third.content shouldEndWith "\n$ENDED_ON_MAC"
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
    test("a member's reply goes to the member's DM") {
        val r = Rig(); r.pair()
        r.state.pairMember(Pairing("u2", "alex", "dm2", "p2"))
        runBlocking { r.host.out.emit(GatewayOut.Reply("s1", "hi Alex", 0, personId = "p2")); waitFor { r.discord.sent.isNotEmpty() } }
        r.discord.sent.single().first shouldBe "dm2"
        r.close()
    }

    test("reply for an unpaired person is dropped") {
        val r = Rig(); r.pair()
        runBlocking { r.host.out.emit(GatewayOut.Reply("s1", "hi", 0, personId = "gone")); delay(200) }
        r.discord.sent.shouldBeEmpty()
        Files.readString(r.log) shouldContain "no door for gone"
        r.close()
    }

    test("a member's question card is answered on the member's DM") {
        val r = Rig(); r.pair()
        r.state.pairMember(Pairing("u2", "alex", "dm2", "p2"))
        runBlocking {
            r.host.out.emit(GatewayOut.Question("question:s1:q", "Which?", listOf("A", "B"), allowFreeText = false, 0, personId = "p2"))
            waitFor { r.discord.sent.isNotEmpty() }
        }
        r.discord.sent.single().first shouldBe "dm2"
        r.close()
    }

    test("a member pairs with their code") {
        val r = Rig(); r.pair()
        val alex = r.people.add("Alex")
        val code = r.gateway.memberCode(alex.id)
        code shouldBe "111111"
        r.gateway.memberCode(alex.id) shouldBe code   // the live code, not a new one
        r.gateway.memberCodes.value shouldBe mapOf(alex.id to "111111")
        r.dm("111 111", author = "u2", channel = "dm2")
        runBlocking { waitFor { r.state.members.isNotEmpty() } }
        r.state.members.single() shouldBe Pairing("u2", "sam", "dm2", alex.id)
        r.gateway.members.value shouldBe listOf(Pairing("u2", "sam", "dm2", alex.id))
        r.gateway.memberCodes.value shouldBe emptyMap()
        r.host.received.shouldBeEmpty()
        r.close()
    }

    test("a member's message comes from the member") {
        val r = Rig(); val alex = r.pairAlex()
        r.dm("add eggs", author = "u2", channel = "dm2")
        runBlocking { waitFor { r.host.received.isNotEmpty() } }
        (r.host.received.single() as GatewayIn.Message).from shouldBe GatewayAddress(alex.id, "discord:u2")
        r.close()
    }

    test("replies to a member's DM go to the member's channel") {
        val r = Rig(); r.pairAlex()
        r.host.result = { if (it is GatewayIn.NewChat) GatewayResult.NewChatStarted else GatewayResult.Sent("phone-1") }
        r.dm("new", author = "u2", channel = "dm2")
        r.dm("", author = "u2", channel = "dm2", extra = ""","attachments":[{"url":"https://cdn.discordapp.com/a.pdf","content_type":"application/pdf","size":1000}]""")
        runBlocking { waitFor { r.discord.sent.size == 2 } }
        r.discord.sent shouldBe listOf("dm2" to OutgoingMessage(NEW_CHAT_STARTED_MEMBER), "dm2" to OutgoingMessage(TAKES_TEXT_VOICE_PHOTOS))
        r.close()
    }

    test("unknown user ignored with members paired") {
        val r = Rig(); r.pairAlex()
        r.dm("hi", author = "u9", channel = "dm9")
        runBlocking { waitFor { r.logHas("ignored a DM from u9") } }
        r.host.received.shouldBeEmpty()
        r.close()
    }

    test("five wrong tries make a new member code") {
        val r = Rig(); r.pair()
        val alex = r.people.add("Alex")
        r.gateway.memberCode(alex.id) shouldBe "111111"
        repeat(5) { r.dm("000000", author = "u9", channel = "dm9") }
        runBlocking { waitFor { r.gateway.memberCodes.value[alex.id] == "222222" } }
        r.dm("111111", author = "u9", channel = "dm9")
        runBlocking { delay(100) }
        r.state.members.shouldBeEmpty()
        r.close()
    }

    test("the owner can't pair as a member") {
        val r = Rig(); r.pair()
        val alex = r.people.add("Alex")
        r.dm(r.gateway.memberCode(alex.id))
        runBlocking { waitFor { r.host.received.isNotEmpty() } }
        (r.host.received.single() as GatewayIn.Message).from.personId shouldBe r.people.owner().id
        r.state.members.shouldBeEmpty()
        r.close()
    }

    test("a member code sent in a server is ignored") {
        val r = Rig(); r.pair()
        val alex = r.people.add("Alex")
        r.dm(r.gateway.memberCode(alex.id), author = "u2", channel = "c1", extra = ""","guild_id":"g1"""")
        runBlocking { waitFor { r.logHas("server message from u2") } }
        r.state.members.shouldBeEmpty()
        r.close()
    }

    test("member can't answer the owner's card") {
        val r = Rig(); r.pairAlex()
        runBlocking { r.host.out.emit(GatewayOut.Permission("permission:k1", "s1", "Chat", "Send email", canAnswer = true, tainted = false, 1_000_000L)) }
        runBlocking { waitFor { r.discord.sent.isNotEmpty() } }
        r.click("p:permission:k1:allow", user = "u2")
        runBlocking { waitFor { r.discord.answers.isNotEmpty() } }
        r.discord.answers.single().content shouldBe "**SoPhi asks** · Chat\nSend email"
        r.host.received.shouldBeEmpty()
        r.close()
    }

    test("a member never gets permission or plan cards") {
        val r = Rig(); r.pairAlex()
        runBlocking {
            r.host.out.emit(GatewayOut.Permission("permission:k1", "s1", "Chat", "Send email", canAnswer = true, tainted = false, 1_000_000L))
            r.host.out.emit(GatewayOut.Plan("plan:p1", "s1", "Chat", "offboard jane", listOf("okta deactivate_user"), canAnswer = true, 1_000_000L))
            waitFor { r.discord.sent.size == 2 }
        }
        r.discord.sent.map { it.first } shouldBe listOf("dm1", "dm1")
        r.close()
    }

    test("a member answers only their own question card") {
        val r = Rig(); val alex = r.pairAlex()
        r.host.result = { GatewayResult.Answered(true) }
        runBlocking {
            r.host.out.emit(GatewayOut.Question("question:s1:q", "Which?", listOf("A", "B"), allowFreeText = false, 0, personId = alex.id))
            waitFor { r.discord.sent.isNotEmpty() }
        }
        r.click("q:question:s1:q:0", user = "u1")   // the owner can't click it
        runBlocking { waitFor { r.discord.answers.isNotEmpty() } }
        r.discord.answers.single().content shouldBe "**SoPhi asks** · Chat\nSend email"
        r.host.received.shouldBeEmpty()
        r.click("q:question:s1:q:0", user = "u2")
        runBlocking { waitFor { r.host.received.isNotEmpty() } }
        (r.host.received.single() as GatewayIn.AnswerQuestion).let { it.from shouldBe GatewayAddress(alex.id, "discord:u2"); it.key shouldBe "question:s1:q" }
        r.close()
    }

    test("a member's question card resolved elsewhere is edited on their DM") {
        val r = Rig(); val alex = r.pairAlex()
        runBlocking {
            r.host.out.emit(GatewayOut.Question("question:s1:q", "Which?", listOf("A", "B"), allowFreeText = false, 0, personId = alex.id))
            waitFor { r.discord.sent.isNotEmpty() }
            r.host.out.emit(GatewayOut.Resolved("question:s1:q", Resolution.ELSEWHERE, 1_000_000L))
            waitFor { r.discord.edits.isNotEmpty() }
        }
        r.discord.edits.single().first shouldBe "dm2"
        r.close()
    }

    test("remove a member") {
        val r = Rig(); val alex = r.pairAlex()
        runBlocking {
            r.host.out.emit(GatewayOut.Question("question:s1:q", "Which?", listOf("A", "B"), allowFreeText = false, 0, personId = alex.id))
            waitFor { r.discord.sent.isNotEmpty() }
        }
        r.gateway.removeMember(alex.id)
        r.state.members.shouldBeEmpty()
        r.gateway.members.value.shouldBeEmpty()
        r.dm("hi", author = "u2", channel = "dm2")
        r.click("q:question:s1:q:0", user = "u2")
        runBlocking { waitFor { r.logHas("ignored a DM from u2") && r.discord.answers.isNotEmpty() } }
        r.host.received.shouldBeEmpty()
        runBlocking { r.host.out.emit(GatewayOut.Resolved("question:s1:q", Resolution.ELSEWHERE, 1_000_000L)); delay(100) }
        r.discord.edits.shouldBeEmpty()   // the card was dropped
        r.gateway.removeMember(alex.id)   // not paired: a no-op
        r.close()
    }

    test("removing a member drops their live code") {
        val r = Rig(); r.pair()
        val alex = r.people.add("Alex")
        val code = r.gateway.memberCode(alex.id)
        r.gateway.removeMember(alex.id)
        r.gateway.memberCodes.value shouldBe emptyMap()
        r.dm(code, author = "u2", channel = "dm2")
        runBlocking { waitFor { r.logHas("ignored a DM from u2") } }
        r.state.members.shouldBeEmpty()
        r.close()
    }

    test("a member code for an unknown person is refused") {
        val r = Rig(); r.pair()
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { r.gateway.memberCode("nobody") }
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { r.gateway.memberCode(r.people.owner().id) }
        r.close()
    }

    test("a burst of wrong guesses spends the member code at once") {
        val r = Rig(); r.pair()
        val alex = r.people.add("Alex")
        val code = r.gateway.memberCode(alex.id)
        repeat(5) { r.dm("000000", author = "u9", channel = "dm9") }
        r.dm(code, author = "u9", channel = "dm9")   // no wait for the 1-second loop
        runBlocking { delay(200) }
        r.state.members.shouldBeEmpty()
        r.gateway.memberCode(alex.id) shouldNotBe code
        r.close()
    }

    test("an expired member code doesn't pair and isn't handed out") {
        val r = Rig(); r.pair()
        val alex = r.people.add("Alex")
        val code = r.gateway.memberCode(alex.id)
        r.now += 10 * 60_000L + 1
        r.dm(code, author = "u2", channel = "dm2")
        runBlocking { delay(200) }
        r.state.members.shouldBeEmpty()
        r.gateway.memberCode(alex.id) shouldNotBe code
        r.close()
    }

    test("a member taken out of People has no door left") {
        val r = Rig(); val alex = r.pairAlex()
        runBlocking {
            r.host.out.emit(GatewayOut.Question("question:s1:q", "Which?", listOf("A", "B"), allowFreeText = false, 0, personId = alex.id))
            waitFor { r.discord.sent.isNotEmpty() }
        }
        r.people.remove(alex.id).shouldBeTrue()   // without removeMember
        r.dm("hi", author = "u2", channel = "dm2")
        r.click("q:question:s1:q:0", user = "u2")
        runBlocking { waitFor { r.logHas("ignored a DM from u2") && r.discord.answers.isNotEmpty() } }
        r.discord.answers.single().content shouldBe "**SoPhi asks** · Chat\nSend email"
        r.host.received.shouldBeEmpty()
        r.close()
    }

    test("rebind ends a member's card with the member's copy") {
        val r = Rig(); val alex = r.pairAlex()
        runBlocking {
            r.host.out.emit(GatewayOut.Question("question:s1:q", "Which?", listOf("A", "B"), allowFreeText = false, 0, personId = alex.id))
            r.host.out.emit(GatewayOut.Permission("permission:s1", "s1", "Chat", "Send email", canAnswer = true, tainted = false, 1_000_000L))
            waitFor { r.discord.sent.size == 2 }
        }
        r.gateway.bind(FakeGatewayHost())
        runBlocking { waitFor { r.discord.edits.size == 2 } }
        r.discord.edits.single { it.first == "dm2" }.third.content shouldEndWith "\n$QUESTION_ENDED_MEMBER"
        r.discord.edits.single { it.first == "dm1" }.third.content shouldEndWith "\n$ENDED_ON_MAC"
        r.close()
    }

    test("a notice for a member goes to the member's DM") {
        val r = Rig(); val alex = r.pairAlex()
        runBlocking { r.host.out.emit(GatewayOut.Notice("Reminder", "Bins out", 0, personId = alex.id)); waitFor { r.discord.sent.isNotEmpty() } }
        r.discord.sent.single().first shouldBe "dm2"
        r.close()
    }

    test("a member can't answer the owner's question card") {
        val r = Rig(); r.pairAlex()
        runBlocking {
            r.host.out.emit(GatewayOut.Question("question:s1:q", "Which?", listOf("A", "B"), allowFreeText = false, 0))
            waitFor { r.discord.sent.isNotEmpty() }
        }
        r.discord.sent.single().first shouldBe "dm1"
        r.click("q:question:s1:q:0", user = "u2")
        runBlocking { waitFor { r.discord.answers.isNotEmpty() } }
        r.discord.answers.single().content shouldBe "**SoPhi asks** · Chat\nSend email"
        r.host.received.shouldBeEmpty()
        r.close()
    }
})
