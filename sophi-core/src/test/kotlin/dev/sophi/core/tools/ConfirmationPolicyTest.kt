package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConfirmationPolicyTest : FunSpec({
    test("ALLOW_ALL always confirms") {
        ConfirmationPolicy.ALLOW_ALL.confirm("bash", "{}") shouldBe true
    }

    test("DENY_DESTRUCTIVE never confirms") {
        ConfirmationPolicy.DENY_DESTRUCTIVE.confirm("bash", "{}") shouldBe false
    }
})
