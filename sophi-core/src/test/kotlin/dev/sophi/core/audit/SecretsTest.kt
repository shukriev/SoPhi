package dev.sophi.core.audit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SecretsTest : FunSpec({
    test("redactSecrets replaces each token type") {
        val cases = mapOf(
            "private_key" to "-----BEGIN RSA PRIVATE KEY-----\nxyz\n-----END RSA PRIVATE KEY-----",
            "github_token" to "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
            "aws_key" to "AKIAIOSFODNN7EXAMPLE",
            "slack_token" to "xoxb-1234567890-0987654321-abcdefghijkl",
            "jwt" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
            "api_key" to "sk-abcdefghijklmnopqrstuvwx",
            "bearer" to "Bearer abc123xyz",
        )
        for ((type, raw) in cases) {
            val out = redactSecrets("x $raw y")
            out shouldContain "[REDACTED:$type]"
            out shouldNotContain raw
        }
    }

    test("modern sk- keys are redacted whole") {
        for (k in listOf("sk-ant-api03-AbCdEf_GhIj-KlMnOpQrStUvWxYz0123", "sk-proj-AbCdEfGhIjKlMnOpQrStUv_x-y", "sk-svcacct-AbCdEfGhIjKlMnOpQrStUv")) {
            redactSecrets("key $k end") shouldBe "key [REDACTED:api_key] end"
        }
    }

    test("Basic and Bearer credentials are redacted to the next whitespace or quote") {
        redactSecrets("Authorization: Basic dXNlcjpwYXNzd29yZA==") shouldBe "Authorization: [REDACTED:bearer]"
        redactSecrets("Authorization: Bearer ab+cd/ef==gh rest") shouldBe "Authorization: [REDACTED:bearer] rest"
        redactSecrets("\"Bearer s3cret\"") shouldBe "\"[REDACTED:bearer]\""
    }

    test("secret-named assignments inside text keep the name and drop the value") {
        redactSecrets("API_KEY=hunter2secretvalue") shouldBe "API_KEY=[REDACTED:secret]"
        redactSecrets("DB_PASSWORD: s3cret!") shouldBe "DB_PASSWORD: [REDACTED:secret]"
        val env = redactSecrets("AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\nHOME=/root")
        env shouldBe "AWS_SECRET_ACCESS_KEY=[REDACTED:secret]\nHOME=/root"
        redactSecrets("""{"password":"x","user":"bob"}""") shouldBe """{"password":"[REDACTED:secret]","user":"bob"}"""
        redactSecrets("""{\"password\":\"x\"}""") shouldNotContain "x\\"
        // already redacted by an earlier pattern: not counted or rewritten twice
        redactSecrets("token=sk-abcdefghijklmnopqrstuvwx") shouldBe "token=[REDACTED:api_key]"
    }

    test("redactArguments redacts assignments inside string values") {
        val out = redactArguments("""{"path":".env","content":"API_KEY=hunter2secretvalue\nHOME=/root"}""")
        out shouldNotContain "hunter2secretvalue"
        out shouldContain "HOME=/root"
        Json.parseToJsonElement(out).jsonObject["content"]!!.jsonPrimitive.content shouldContain "API_KEY=[REDACTED:secret]"
    }

    test("redactArguments hides secret values and token patterns") {
        val input = """{"command":"curl -H \"Authorization: Bearer abcdefghijklmnop1234\" x","password":"hunter2","nested":{"apiKey":"v"},"list":[{"token":"t"}]}"""
        val out = redactArguments(input)
        out shouldNotContain "abcdefghijklmnop1234"
        out shouldNotContain "hunter2"
        out shouldNotContain "\"v\""
        out shouldNotContain "\"t\""
        val obj = Json.parseToJsonElement(out).jsonObject
        obj["command"]!!.jsonPrimitive.content shouldContain "[REDACTED:bearer]"
        obj["command"]!!.jsonPrimitive.content shouldContain "curl"
        obj["password"]!!.jsonPrimitive.content shouldBe "[REDACTED]"
    }

    test("a secret-named key redacts an object or array value whole") {
        val out = redactArguments("""{"credentials":{"a":"x"},"private_key":["y"],"ok":1}""")
        out shouldNotContain "\"x\""
        out shouldNotContain "\"y\""
        out shouldContain "\"ok\":1"
    }

    test("redactArguments on non-JSON redacts tokens only") {
        val out = redactArguments("not json sk-abcdefghijklmnopqrstuvwx password=x")
        out shouldContain "[REDACTED:api_key]"
        out shouldNotContain "password=x"
        out shouldContain "password=[REDACTED:secret]"
    }

    test("kebab-case words containing sk- are left alone") {
        for (t in listOf("task-management-system-overview", "ask-for-help-with-the-deployment", "risk-assessment-for-the-quarter")) {
            redactSecrets(t) shouldBe t
        }
    }

    test("quoted and escaped values are redacted whole, closing quote kept") {
        redactSecrets("""PASSWORD="a b c" X=1""") shouldBe """PASSWORD="[REDACTED:secret]" X=1"""
        redactSecrets("""{"password": "has space"}""") shouldBe """{"password": "[REDACTED:secret]"}"""
        redactSecrets("""authToken="abc\"def" next""") shouldBe """authToken="[REDACTED:secret]" next"""
        redactSecrets("""{\"password\":\"a b\",\"u\":1}""") shouldBe """{\"password\":\"[REDACTED:secret]\",\"u\":1}"""
        redactSecrets("'client_secret': 'x y'") shouldBe "'client_secret': '[REDACTED:secret]'"
    }

    test("an unquoted value stops at whitespace, a quote or a backslash") {
        redactSecrets("""PASSWORD=abc\nX=1""") shouldBe """PASSWORD=[REDACTED:secret]\nX=1"""
        redactSecrets("PASSWORD=\nX=1") shouldBe "PASSWORD=\nX=1"
    }

    test("ordinary code and config are not mangled") {
        for (t in listOf(
            "max_tokens=4096", "tokens: 123", "token_count=5", "use_token: true", "if token == expected:",
            "const token = getToken();", "token = null", "password = 12", """{"max_tokens":4096,"stream":true}""",
        )) {
            redactSecrets(t) shouldBe t
        }
    }

    test("Bearer and Basic need an Authorization context or a token shape") {
        for (t in listOf("Basic configuration here", "basic authentication", "The bearer of bad news")) redactSecrets(t) shouldBe t
        redactSecrets("Authorization: Bearer abcdefgh") shouldBe "Authorization: [REDACTED:bearer]"
        redactSecrets("authorization=basic dXNlcg") shouldBe "authorization=[REDACTED:bearer]"
    }

    test("secret names with a key/base/value/id suffix are redacted") {
        redactSecrets("SECRET_KEY=abc123def") shouldBe "SECRET_KEY=[REDACTED:secret]"
        redactSecrets("STRIPE_SECRET_KEY=sk_live_abcdef123") shouldBe "STRIPE_SECRET_KEY=[REDACTED:secret]"
        redactSecrets("""DJANGO_SECRET_KEY="x y"""") shouldBe """DJANGO_SECRET_KEY="[REDACTED:secret]""""
        redactSecrets("SECRET_KEY_BASE=abc") shouldBe "SECRET_KEY_BASE=[REDACTED:secret]"
        redactSecrets("GH_TOKEN_VALUE=abc") shouldBe "GH_TOKEN_VALUE=[REDACTED:secret]"
        redactSecrets("PRIVATE_KEY=abc") shouldBe "PRIVATE_KEY=[REDACTED:secret]"
        redactSecrets("secretKey: abc") shouldBe "secretKey: [REDACTED:secret]"
    }
})
