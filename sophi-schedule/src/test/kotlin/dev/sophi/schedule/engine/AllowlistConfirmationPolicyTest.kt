package dev.sophi.schedule.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class AllowlistConfirmationPolicyTest : FunSpec({
    test("confirm returns true only for tool names on the allowlist") {
        val policy = AllowlistConfirmationPolicy(setOf("fetch_url"))
        runBlocking {
            policy.confirm("fetch_url", "{}") shouldBe true
            policy.confirm("write_file", "{}") shouldBe false
        }
    }

    test("empty allowlist denies everything") {
        val policy = AllowlistConfirmationPolicy(emptySet())
        runBlocking { policy.confirm("fetch_url", "{}") shouldBe false }
    }
})
