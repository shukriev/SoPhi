package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class ConfirmationPolicyTest : FunSpec({
    test("ALLOW_ALL confirms every request in the batch") {
        val requests = listOf(
            ConfirmationRequest("c1", "bash", "{}", RiskLevel.DESTRUCTIVE),
            ConfirmationRequest("c2", "create_calendar_event", "{}", RiskLevel.CAUTION)
        )
        runBlocking { ConfirmationPolicy.ALLOW_ALL.confirm(requests) } shouldBe
            mapOf("c1" to true, "c2" to true)
    }

    test("DENY_ALL denies every request in the batch") {
        val requests = listOf(
            ConfirmationRequest("c1", "bash", "{}", RiskLevel.DESTRUCTIVE),
            ConfirmationRequest("c2", "create_calendar_event", "{}", RiskLevel.CAUTION)
        )
        runBlocking { ConfirmationPolicy.DENY_ALL.confirm(requests) } shouldBe
            mapOf("c1" to false, "c2" to false)
    }
})
