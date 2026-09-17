package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class ThresholdConfirmationPolicyTest : FunSpec({
    test("requests below the threshold auto-approve without calling the fallback") {
        var fallbackCalls = 0
        val fallback = ConfirmationPolicy { requests -> fallbackCalls++; requests.associate { it.callId to false } }
        val policy = ThresholdConfirmationPolicy(RiskLevel.DESTRUCTIVE, fallback)

        val result = runBlocking {
            policy.confirm(listOf(ConfirmationRequest("c1", "some_tool", "{}", RiskLevel.CAUTION)))
        }

        result shouldBe mapOf("c1" to true)
        fallbackCalls shouldBe 0
    }

    test("requests at or above the threshold are forwarded to the fallback") {
        val fallback = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
        val policy = ThresholdConfirmationPolicy(RiskLevel.CAUTION, fallback)

        val result = runBlocking {
            policy.confirm(listOf(ConfirmationRequest("c1", "some_tool", "{}", RiskLevel.CAUTION)))
        }

        result shouldBe mapOf("c1" to false)
    }

    test("threshold DESTRUCTIVE only prompts on DESTRUCTIVE, auto-approving CAUTION (freedom mode)") {
        val fallback = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
        val policy = ThresholdConfirmationPolicy(RiskLevel.DESTRUCTIVE, fallback)

        val result = runBlocking {
            policy.confirm(listOf(
                ConfirmationRequest("c1", "caution_tool", "{}", RiskLevel.CAUTION),
                ConfirmationRequest("c2", "destructive_tool", "{}", RiskLevel.DESTRUCTIVE)
            ))
        }

        result shouldBe mapOf("c1" to true, "c2" to false)
    }

    test("threshold CAUTION prompts on both CAUTION and DESTRUCTIVE (today's default behavior)") {
        val fallback = ConfirmationPolicy { requests -> requests.associate { it.callId to false } }
        val policy = ThresholdConfirmationPolicy(RiskLevel.CAUTION, fallback)

        val result = runBlocking {
            policy.confirm(listOf(
                ConfirmationRequest("c1", "caution_tool", "{}", RiskLevel.CAUTION),
                ConfirmationRequest("c2", "destructive_tool", "{}", RiskLevel.DESTRUCTIVE)
            ))
        }

        result shouldBe mapOf("c1" to false, "c2" to false)
    }

    test("a mixed batch only sends the at-or-above-threshold subset to the fallback") {
        val fallbackBatchSizes = mutableListOf<Int>()
        val fallback = ConfirmationPolicy { requests ->
            fallbackBatchSizes.add(requests.size)
            requests.associate { it.callId to false }
        }
        val policy = ThresholdConfirmationPolicy(RiskLevel.DESTRUCTIVE, fallback)

        runBlocking {
            policy.confirm(listOf(
                ConfirmationRequest("c1", "caution_tool", "{}", RiskLevel.CAUTION),
                ConfirmationRequest("c2", "destructive_tool", "{}", RiskLevel.DESTRUCTIVE)
            ))
        }

        fallbackBatchSizes shouldBe listOf(1)
    }

    test("no requests reach the threshold: fallback is never called") {
        var fallbackCalls = 0
        val fallback = ConfirmationPolicy { requests -> fallbackCalls++; requests.associate { it.callId to false } }
        val policy = ThresholdConfirmationPolicy(RiskLevel.DESTRUCTIVE, fallback)

        val result = runBlocking {
            policy.confirm(listOf(ConfirmationRequest("c1", "safe_tool", "{}", RiskLevel.SAFE)))
        }

        result shouldBe mapOf("c1" to true)
        fallbackCalls shouldBe 0
    }
})
