package dev.sophi.gateway.discord

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private fun op(json: String) = Json.parseToJsonElement(json).jsonObject

class DiscordSessionTest : FunSpec({
    val hello = """{"op":10,"d":{"heartbeat_interval":41250}}"""
    val ready = """{"op":0,"s":1,"t":"READY","d":{"session_id":"abc","resume_gateway_url":"wss://resume.discord.gg","user":{"username":"SoPhi"},"application":{"id":"9"}}}"""

    test("hello then identify with intents 4097") {
        val actions = DiscordSession("tok").onPayload(hello)
        actions[0] shouldBe DiscordAction.StartHeartbeat(41250)
        val identify = op((actions[1] as DiscordAction.Send).json)
        identify["op"]!!.jsonPrimitive.int shouldBe 2
        identify["d"]!!.jsonObject["token"]!!.jsonPrimitive.content shouldBe "tok"
        identify["d"]!!.jsonObject["intents"]!!.jsonPrimitive.int shouldBe 4097
    }

    test("ready stores session and resume url") {
        val s = DiscordSession("tok")
        s.onPayload(hello)
        val d = s.onPayload(ready).single()
        d.shouldBeInstanceOf<DiscordAction.Dispatch>().type shouldBe "READY"
        s.sessionId shouldBe "abc"
        s.resumeUrl shouldBe "wss://resume.discord.gg"
        s.seq shouldBe 1L
    }

    test("hello after ready resumes with seq") {
        val s = DiscordSession("tok")
        s.onPayload(hello); s.onPayload(ready)
        s.onPayload("""{"op":0,"s":7,"t":"MESSAGE_CREATE","d":{}}""")
        val resume = op((s.onPayload(hello)[1] as DiscordAction.Send).json)
        resume["op"]!!.jsonPrimitive.int shouldBe 6
        resume["d"]!!.jsonObject["session_id"]!!.jsonPrimitive.content shouldBe "abc"
        resume["d"]!!.jsonObject["seq"]!!.jsonPrimitive.long shouldBe 7L
        op(s.heartbeat())["d"]!!.jsonPrimitive.long shouldBe 7L
    }

    test("invalid session false clears and re-identifies") {
        val s = DiscordSession("tok")
        s.onPayload(hello); s.onPayload(ready)
        s.onPayload("""{"op":9,"d":false}""").single() shouldBe DiscordAction.Reconnect(resume = false)
        s.sessionId shouldBe null
        op((s.onPayload(hello)[1] as DiscordAction.Send).json)["op"]!!.jsonPrimitive.int shouldBe 2
        s.onPayload("""{"op":7,"d":null}""").single() shouldBe DiscordAction.Reconnect(resume = true)
        s.onPayload("""{"op":11}""").single() shouldBe DiscordAction.HeartbeatAck
    }

    test("4004 is fatal with the token text") {
        DiscordSession("tok").onClosed(4004) shouldBe DiscordAction.Fatal("Discord didn't accept this token.")
        DiscordSession("tok").onClosed(4014) shouldBe DiscordAction.Fatal("Discord refused the intents SoPhi asks for.")
    }

    test("4009 reconnects without resume") {
        val s = DiscordSession("tok")
        s.onPayload(hello); s.onPayload(ready)
        s.onClosed(4009) shouldBe DiscordAction.Reconnect(resume = false)
        s.sessionId shouldBe null
        val live = DiscordSession("tok").apply { onPayload(hello); onPayload(ready) }
        live.onClosed(1006) shouldBe DiscordAction.Reconnect(resume = true)   // a dropped socket resumes
        DiscordSession("tok").onClosed(1006) shouldBe DiscordAction.Reconnect(resume = false)   // nothing to resume
    }
})
