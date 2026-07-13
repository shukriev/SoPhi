package dev.sophi.memory.jane

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class RedactionTest : FunSpec({
    test("credit-card-like numbers are redacted") {
        redact("card 4111 1111 1111 1111 please") shouldNotContain "4111"
        redact("card 4111-1111-1111-1111 please") shouldNotContain "4111"
    }
    test("long digit runs (IDs) are redacted, short ones kept") {
        redact("passport 987654321") shouldNotContain "987654321"
        redact("meet at 14:30 on floor 3") shouldContain "14:30"
    }
    test("secret-keyword assignments are redacted") {
        redact("my password is hunter2") shouldNotContain "hunter2"
        redact("the api_key=sk-abc123XYZ") shouldNotContain "sk-abc123XYZ"
    }
    test("normal text passes through unchanged") {
        redact("Emma starts school on Monday") shouldBe "Emma starts school on Monday"
    }
})
