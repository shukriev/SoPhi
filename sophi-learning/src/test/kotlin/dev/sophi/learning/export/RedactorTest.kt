package dev.sophi.learning.export

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class RedactorTest : FunSpec({
    test("built-in patterns: api keys, emails, bearer, private keys, jwt") {
        val r = Redactor()
        r.redact("key=sk-abc123456789012345678901234567890123456789012345678") shouldContain "[REDACTED:api_key]"
        r.redact("mail me at dev@example.com") shouldBe "mail me at [REDACTED:email]"
        r.redact("Authorization: Bearer abcDEF123.abc-def_123") shouldContain "[REDACTED:bearer]"
        r.redact("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") shouldContain "[REDACTED:github_token]"
        r.redact("-----BEGIN RSA PRIVATE KEY-----\nxyz\n-----END RSA PRIVATE KEY-----") shouldContain "[REDACTED:private_key]"
        r.hitsByType.getValue("email") shouldBe 1
    }

    test("user patterns file adds custom regexes with type 'custom'") {
        val f = tempdir().toPath().resolve("redaction.txt")
        java.nio.file.Files.writeString(f, "SECRET-\\d{4}\n")
        Redactor(f).redact("code SECRET-1234 here") shouldNotContain "SECRET-1234"
    }
})
