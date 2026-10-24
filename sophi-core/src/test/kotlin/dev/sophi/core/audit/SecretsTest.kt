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
        val out = redactSecrets("key sk-abcdefghijklmnopqrstuvwx")
        out shouldContain "[REDACTED:api_key]"
        out shouldNotContain "sk-abcdefghijklmnopqrstuvwx"
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
        out shouldContain "password=x"
    }
})
