package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class ToggleableConfirmationPolicyTest : FunSpec({
    val request = ConfirmationRequest("c1", "bash", "{}", RiskLevel.DESTRUCTIVE)

    test("routes to the auto policy when autoModeEnabled is true") {
        val auto = ConfirmationPolicy { requests -> requests.associate { it.callId to true } }
        val manual = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
        val policy = ToggleableConfirmationPolicy(auto, manual, autoModeEnabled = true)

        runBlocking { policy.confirm(listOf(request)) } shouldBe mapOf("c1" to true)
    }

    test("routes to the manual policy when autoModeEnabled is false") {
        val auto = ConfirmationPolicy { requests -> requests.associate { it.callId to true } }
        val manual = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
        val policy = ToggleableConfirmationPolicy(auto, manual, autoModeEnabled = false)

        runBlocking { policy.confirm(listOf(request)) } shouldBe mapOf("c1" to false)
    }

    test("flipping autoModeEnabled at runtime changes routing without reconstructing the policy") {
        val auto = ConfirmationPolicy { requests -> requests.associate { it.callId to true } }
        val manual = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
        val policy = ToggleableConfirmationPolicy(auto, manual, autoModeEnabled = false)

        runBlocking { policy.confirm(listOf(request)) } shouldBe mapOf("c1" to false)
        policy.autoModeEnabled = true
        runBlocking { policy.confirm(listOf(request)) } shouldBe mapOf("c1" to true)
    }
})
