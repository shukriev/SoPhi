package dev.sophi.learning.export

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class RedactorTest : FunSpec({
    test("built-in patterns: api keys, emails, bearer, private keys, jwt, aws, slack") {
        val r = Redactor()
        r.redact("key=sk-abc123456789012345678901234567890123456789012345678") shouldContain "[REDACTED:api_key]"
        r.redact("mail me at dev@example.com") shouldBe "mail me at [REDACTED:email]"
        r.redact("Authorization: Bearer abcDEF123.abc-def_123") shouldContain "[REDACTED:bearer]"
        r.redact("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") shouldContain "[REDACTED:github_token]"
        r.redact("-----BEGIN RSA PRIVATE KEY-----\nxyz\n-----END RSA PRIVATE KEY-----") shouldContain "[REDACTED:private_key]"

        // JWT: three base64url segments separated by dots, each 10+ chars
        r.redact("token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U") shouldContain "[REDACTED:jwt]"

        // AWS key: AKIA + 16 uppercase alphanumerics
        r.redact("aws_access_key_id=AKIAIOSFODNN7EXAMPLE") shouldContain "[REDACTED:aws_key]"

        // Slack token: xoxb- + 10+ token chars
        r.redact("slack token xoxb-1234567890-0987654321-abcdefghijkl for channel") shouldContain "[REDACTED:slack_token]"

        r.hitsByType.getValue("email") shouldBe 1
        r.hitsByType.getValue("jwt") shouldBe 1
        r.hitsByType.getValue("aws_key") shouldBe 1
        r.hitsByType.getValue("slack_token") shouldBe 1
    }

    test("user patterns file adds custom regexes with type 'custom'") {
        val f = tempdir().toPath().resolve("redaction.txt")
        java.nio.file.Files.writeString(f, "SECRET-\\d{4}\n")
        val r = Redactor(f)
        r.redact("code SECRET-1234 here") shouldNotContain "SECRET-1234"
        r.hitsByType.getValue("custom") shouldBe 1
    }
})
