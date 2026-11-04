package dev.sophi.sdk.rpc

import dev.sophi.core.agent.TurnEvent
import dev.sophi.ai.api.TokenUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RpcProtocolTest : FunSpec({
    test("every command decodes from its wire form") {
        fun d(s: String) = decodeRpcCommand(s).getOrThrow()
        d("""{"type":"prompt","text":"hi"}""") shouldBe RpcCommand.Prompt("hi")
        d("""{"type":"follow_up","text":"more"}""") shouldBe RpcCommand.FollowUp("more")
        d("""{"type":"steer","text":"left"}""") shouldBe RpcCommand.Steer("left")
        d("""{"type":"abort"}""") shouldBe RpcCommand.Abort()
        d("""{"type":"confirm","callId":"c1","approved":true,"id":"7"}""") shouldBe RpcCommand.Confirm("c1", true, id = "7")
        d("""{"type":"compact"}""") shouldBe RpcCommand.Compact()
        d("""{"type":"branch"}""") shouldBe RpcCommand.Branch()
        d("""{"type":"checkout","entryId":"e1"}""") shouldBe RpcCommand.Checkout("e1")
        d("""{"type":"checkout","entryId":"e1","files":true}""") shouldBe RpcCommand.Checkout("e1", true)
        d("""{"type":"set_model","model":"m"}""") shouldBe RpcCommand.SetModel("m")
        d("""{"type":"goal","text":"g"}""") shouldBe RpcCommand.Goal("g")
        d("""{"type":"goal","text":"g","check":"true"}""") shouldBe RpcCommand.Goal("g", "true")
    }

    test("unknown type and bad json fail") {
        decodeRpcCommand("""{"type":"nope"}""").isFailure shouldBe true
        decodeRpcCommand("not json").isFailure shouldBe true
    }

    test("an event is one line however odd the text") {
        val text = "a\n\"b\" é 🙂"
        val line = encodeRpcEvent(RpcEvent.Token(text))
        line.contains('\n') shouldBe false
        val obj = Json.parseToJsonElement(line).jsonObject
        obj["type"]!!.jsonPrimitive.content shouldBe "token"
        obj["text"]!!.jsonPrimitive.content shouldBe text
    }

    test("turn events map and mask") {
        TurnEvent.ToolCallStarted("bash", """{"k":"s3cr3t"}""").toRpcEvent { it.replace("s3cr3t", "***") } shouldBe
            RpcEvent.ToolCallStarted("bash", """{"k":"***"}""")
        TurnEvent.RoundUsage(TokenUsage(3, 4), 50).toRpcEvent { it } shouldBe RpcEvent.Usage(3, 4, 50)
        TurnEvent.ConfirmationFinished.toRpcEvent { it } shouldBe null
    }
})
