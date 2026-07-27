package dev.sophi.schedule.engine

import dev.sophi.core.tools.ConfirmationRequest
import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class AllowlistConfirmationPolicyTest : FunSpec({
    test("confirm returns true only for tool names on the allowlist") {
        val policy = AllowlistConfirmationPolicy(setOf("fetch_url"))
        val requests = listOf(
            ConfirmationRequest("c1", "fetch_url", "{}", RiskLevel.DESTRUCTIVE),
            ConfirmationRequest("c2", "write_file", "{}", RiskLevel.DESTRUCTIVE)
        )
        runBlocking { policy.confirm(requests) } shouldBe mapOf("c1" to true, "c2" to false)
    }

    test("empty allowlist denies everything") {
        val policy = AllowlistConfirmationPolicy(emptySet())
        val requests = listOf(ConfirmationRequest("c1", "fetch_url", "{}", RiskLevel.DESTRUCTIVE))
        runBlocking { policy.confirm(requests) } shouldBe mapOf("c1" to false)
    }
})
