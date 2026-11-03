package dev.sophi.gateway

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

class GatewayFormatTest : FunSpec({
    val from = GatewayAddress("p1", "discord:42")

    test("round trip") {
        listOf(
            GatewayIn.Message(from, "hi", photoPath = "/a.png", voice = true),
            GatewayIn.NewChat(from),
            GatewayIn.AnswerPermission(from, "k1", allow = true),
            GatewayIn.AnswerQuestion(from, "k2", choiceIndex = 1, text = null),
            GatewayIn.AnswerPlan(from, "plan:p1", approve = false),
        ).forEach { decodeGatewayIn(encodeGatewayIn(it)).getOrThrow() shouldBe it }
    }

    test("a reply without personId still decodes") {
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        val frame = json.decodeFromString(GatewayFrame.serializer(), """{"v":1,"body":{"type":"reply","sessionId":"s1","text":"hi","createdAtMs":0}}""")
        (json.decodeFromJsonElement(GatewayOut.serializer(), frame.body) as GatewayOut.Reply).personId shouldBe null
    }

    test("unknown version is refused") {
        decodeGatewayIn("""{"v":2,"body":{}}""").exceptionOrNull()!!.message!! shouldContain "version 2"
    }
})

class GatewayTextTest : FunSpec({
    test("parseMessage hides the photo text and spots voice") {
        parseMessage(photoMessage("look", "Total 42"), hasImages = true) shouldBe Shown(voice = false, body = "look")
        parseMessage(voiceMessage("what's on today"), hasImages = false) shouldBe Shown(voice = true, body = "what's on today")
        parseMessage("plain\n\nText in the photo:\nnot a photo", hasImages = false).body shouldBe "plain\n\nText in the photo:\nnot a photo"
    }
})
