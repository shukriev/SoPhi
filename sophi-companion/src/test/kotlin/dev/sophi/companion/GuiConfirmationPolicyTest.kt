package dev.sophi.companion

import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

class GuiConfirmationPolicyTest : FunSpec({
    test("confirm notifies with the tool names, then delegates to onConfirmationNeeded with the session id from context") {
        var notifiedTitle: String? = null
        var notifiedBody: String? = null
        var delegatedSessionId: String? = null
        var delegatedRequests: List<ConfirmationRequest>? = null
        val policy = GuiConfirmationPolicy(
            notify = { t, b -> notifiedTitle = t; notifiedBody = b },
            onConfirmationNeeded = { sessionId, requests ->
                delegatedSessionId = sessionId
                delegatedRequests = requests
                requests.associate { it.callId to true }
            }
        )
        val requests = listOf(ConfirmationRequest("c1", "write_file", "{}", RiskLevel.DESTRUCTIVE))

        val result = runBlocking(SessionIdContext("session-42")) { policy.confirm(requests) }

        notifiedTitle shouldBe "Sophi needs confirmation"
        notifiedBody.shouldNotBeNull()
        notifiedBody!! shouldContain "write_file"
        delegatedSessionId shouldBe "session-42"
        delegatedRequests shouldBe requests
        result shouldBe mapOf("c1" to true)
    }

    test("confirm's notification body names every tool when there are multiple requests") {
        var notifiedBody: String? = null
        val policy = GuiConfirmationPolicy(
            notify = { _, b -> notifiedBody = b },
            onConfirmationNeeded = { _, requests -> requests.associate { it.callId to false } }
        )
        val requests = listOf(
            ConfirmationRequest("c1", "write_file", "{}", RiskLevel.DESTRUCTIVE),
            ConfirmationRequest("c2", "delete_file", "{}", RiskLevel.DESTRUCTIVE)
        )

        runBlocking(SessionIdContext("session-1")) { policy.confirm(requests) }

        notifiedBody!! shouldContain "write_file"
        notifiedBody!! shouldContain "delete_file"
    }

    test("confirm denies every request without calling onConfirmationNeeded when no session id is in context") {
        var callbackInvoked = false
        val policy = GuiConfirmationPolicy(
            notify = { _, _ -> },
            onConfirmationNeeded = { _, requests -> callbackInvoked = true; requests.associate { it.callId to true } }
        )
        val requests = listOf(
            ConfirmationRequest("c1", "write_file", "{}", RiskLevel.DESTRUCTIVE),
            ConfirmationRequest("c2", "delete_file", "{}", RiskLevel.DESTRUCTIVE)
        )

        val result = runBlocking { policy.confirm(requests) }  // no SessionIdContext set

        callbackInvoked shouldBe false
        result shouldBe mapOf("c1" to false, "c2" to false)
    }
})
