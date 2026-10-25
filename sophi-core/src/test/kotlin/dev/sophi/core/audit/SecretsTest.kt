package dev.sophi.core.audit

import dev.sophi.core.tools.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
    // Each redaction on its own thread with the JVM's default stack, as a tool coroutine would run it.
    fun onDefaultStack(block: () -> String): String {
        var out: Result<String>? = null
        Thread { out = runCatching(block) }.apply { start(); join() }
        return out!!.getOrThrow()
    }

    test("a huge quoted secret value, terminated or not, is redacted without overflowing the stack") {
        val big = "A".repeat(120_000)
        onDefaultStack { redactSecrets("secret=\"$big\" tail") } shouldBe "secret=\"[REDACTED:secret]\" tail"
        onDefaultStack { redactSecrets("secret='$big' tail") } shouldBe "secret='[REDACTED:secret]' tail"
        // Unterminated (a fetched page cut mid-attribute): the value up to the next whitespace still goes.
        onDefaultStack { redactSecrets("secret=\"$big") } shouldBe "secret=\"[REDACTED:secret]"
        onDefaultStack { redactSecrets("""{\"token\": \"$big\"}""") } shouldNotContain "AAAA"
        onDefaultStack { redactSecrets("secret=\"" + "a\\\"b".repeat(40_000)) }  // many escapes, unterminated
        val nested = onDefaultStack { redactArguments("""{"content":"{\"token\": \"$big\"}"}""") }
        nested shouldNotContain "AAAA"
        Json.parseToJsonElement(nested)
    }

    test("credentials in URLs, curl -u and vendor key prefixes are redacted") {
        redactSecrets("curl https://admin:hunter2@host/x") shouldBe "curl https://[REDACTED:url_credentials]@host/x"
        redactSecrets("DATABASE_URL=postgres://u:p4ssw0rd@db:5432/app") shouldNotContain "p4ssw0rd"
        redactTokens("postgres://u:p4ssw0rd@db:5432/app") shouldBe "postgres://[REDACTED:url_credentials]@db:5432/app"
        redactSecrets("curl -u admin:hunter2 https://api.example.com") shouldBe "curl -u [REDACTED:basic_auth] https://api.example.com"
        redactSecrets("curl --user admin:hunter2 x") shouldBe "curl --user [REDACTED:basic_auth] x"
        val keys = mapOf(
            "sk_live_" + "a1".repeat(12) to "stripe_key", "sk_test_" + "b2".repeat(12) to "stripe_key",
            "rk_live_" + "c3".repeat(12) to "stripe_key", "github_pat_11ABCDEFG0123456789_abcdefghijklmnop" to "github_token",
            "glpat-abcdefghij1234567890" to "gitlab_token", "AIzaSyA1234567890abcdefghijklmnopqrstuv" to "google_api_key",
        )
        for ((k, type) in keys) redactTokens("use $k now") shouldBe "use [REDACTED:$type] now"
        // plain URLs, ports and e-mail-like text are left alone
        for (t in listOf("https://host:8080/x", "git@github.com:org/repo.git", "mailto:a@b.c", "sort -u file.txt"))
            redactSecrets(t) shouldBe t
    }

    test("redactArguments keeps count-like keys and redacts secret-named ones") {
        val out = Json.parseToJsonElement(redactArguments(
            """{"max_tokens":4096,"token_count":3,"tokens_used":12,"password":"a","apiKey":"b","client_secret":"c","Authorization":"d","access_token":"e","credentials":"f"}"""
        )).jsonObject
        out["max_tokens"]!!.jsonPrimitive.content shouldBe "4096"
        out["token_count"]!!.jsonPrimitive.content shouldBe "3"
        out["tokens_used"]!!.jsonPrimitive.content shouldBe "12"
        for (k in listOf("password", "apiKey", "client_secret", "Authorization", "access_token", "credentials"))
            out[k]!!.jsonPrimitive.content shouldBe "[REDACTED]"
    }
    test("arguments longer than the redaction window still get their secret keys redacted") {
        val args = """{"headers":{"Authorization":"Basic dXNlcjpwYXNz"},"pin_password":123456,"credentials":{"user":"a","pass":"b"},"body":"${"z".repeat(25_000)}"}"""
        val out = auditEntry("a", null, "s", "t", RiskLevel.SAFE, args, "safe", "ok", "", 0).args
        out shouldNotContain "dXNlcjpwYXNz"
        out shouldNotContain "123456"
        out shouldNotContain "\"pass\""
        // The 25k body is one token-shaped run cut by the window, so it goes whole (see redactWindowed).
        Json.parseToJsonElement(out).jsonObject["body"]!!.jsonPrimitive.content shouldBe "…"
    }

    test("an unterminated escaped-quote value in raw text is redacted") {
        redactSecrets("""\"token\": \"hunter2hunter2 AAAA""") shouldBe """\"token\": \"[REDACTED:secret] AAAA"""
        onDefaultStack { redactSecrets("""\"token\": \"""" + "A".repeat(100_000)) } shouldBe """\"token\": \"[REDACTED:secret]"""
    }

    test("secret keys with a header or hash suffix are redacted; look-alike names are kept") {
        val out = Json.parseToJsonElement(redactArguments(
            """{"authorization_header":"a","client_secret_hash":"b","max_tokens":1,"token_count":2,"tokens_used":3,"tokenizer":"cl100k","secretary":"bob"}"""
        )).jsonObject
        out["authorization_header"]!!.jsonPrimitive.content shouldBe "[REDACTED]"
        out["client_secret_hash"]!!.jsonPrimitive.content shouldBe "[REDACTED]"
        for ((k, v) in mapOf("max_tokens" to "1", "token_count" to "2", "tokens_used" to "3", "tokenizer" to "cl100k", "secretary" to "bob"))
            out[k]!!.jsonPrimitive.content shouldBe v
    }

    test("curl's attached -uuser:pass is redacted; other -u/-U flags are unchanged") {
        redactTokens("curl -uadmin:hunter2 x") shouldBe "curl -u[REDACTED:basic_auth] x"
        for (t in listOf("sort -u file.txt", "psql -U admin:x", "sort -ufile.txt", "docker -u1000:1000 img")) redactTokens(t) shouldBe t
        redactTokens("docker -u 1000:1000 img") shouldBe "docker -u [REDACTED:basic_auth] img" // as before: acceptable
    }

    test("a URL password containing @ is redacted up to the last @ before the host") {
        redactTokens("https://a:b@c:d@host/x") shouldBe "https://[REDACTED:url_credentials]@host/x"
        redactTokens("https://host:8080/p?e=a@b") shouldBe "https://host:8080/p?e=a@b"
        redactTokens("https://host:8080?e=a@b") shouldBe "https://host:8080?e=a@b"
        for (t in listOf("git@github.com:org/repo.git", "mailto:a@b.c", "ssh://git@host:22/x")) redactTokens(t) shouldBe t
        val raw = """{"c":"url \"https://a:b@c@h\" C:\\x {u}:p@h"}"""
        Json.parseToJsonElement(redactTokens(raw)) // still parses
    }
    fun entryFor(args: String, result: String = "") = auditEntry("a", null, "s", "t", RiskLevel.SAFE, args, "safe", "ok", result, 0)

    test("suffix bombs are redacted inside the kept output, on a default stack") {
        // No window or cap, so the secret is in the output and the possessive suffix loop is what's exercised.
        onDefaultStack { redactArguments("""{"token${"key".repeat(3000)}":"hunter2","x":1}""") } shouldEndWith """":"[REDACTED]","x":1}"""
        onDefaultStack { redactSecrets("token${"_key".repeat(6000)}=hunter2 tail") } shouldEndWith "_key=[REDACTED:secret] tail"
        // And as entries: written, not dropped.
        onDefaultStack { entryFor("""{"token${"key".repeat(3000)}":"hunter2"}""", "token${"_key".repeat(6000)}=hunter2").args } shouldNotContain "hunter2"
    }

    test("JSON nested too deep to walk keeps its key redaction or fails closed") {
        val args = """{"headers":{"Authorization":"Basic dXNlcjpwYXNz"},"pin_password":123456,"credentials":{"user":"a","pass":"b"},"x":${"[".repeat(5000)}1${"]".repeat(5000)}}"""
        val out = onDefaultStack { entryFor(args).args }
        for (secret in listOf("dXNlcjpwYXNz", "123456", "\"pass\"")) out shouldNotContain secret
        if (out != "[unredactable]") Json.parseToJsonElement(out)
    }

    // 1000, not 5000: kotlinx's own parser overflows a default stack near 5000 levels once warm (that input fails closed, above).
    test("a 1000-deep array is cut at depth 200 with a marker, and the rest is still redacted") {
        val out = onDefaultStack { redactArguments("""{"password":"p","x":${"[".repeat(1000)}1${"]".repeat(1000)}}""") }
        out shouldStartWith """{"password":"[REDACTED]","x":"""
        out shouldContain "[".repeat(200) + "\"[…]\""
        out shouldNotContain "[".repeat(201)
        Json.parseToJsonElement(out)
    }

    test("almost-JSON that fails to parse is unredactable, not text-redacted") {
        val broken = """{"headers":{"Authorization":"Basic dXNlcjpwYXNz"},"pin_password":123456,"credentials":{"user":"a","pass":"b"}"""
        redactArguments(broken) shouldBe "[unredactable]"
        redactArguments("  [1, 2") shouldBe "[unredactable]"
        redactArguments("not json password=x") shouldBe "not json password=[REDACTED:secret]"
    }

    test("a cut URL password holding @ leaves nothing after the scheme") {
        val text = "Bearer ${"A".repeat(17_000)} https://u:p@${"s!".repeat(1500)}@h.com" // 20,026 chars: the window cuts the password
        for (out in listOf(entryFor("""{"b":"$text"}""").args, redactArguments(text, 20_000), redactWindowed(text, 20_000))) {
            out shouldNotContain "s!s!"
            out shouldNotContain "u:p"
        }
    }

    test("empty values and long keys are charged to the work budget") {
        val empties = Json.parseToJsonElement(redactArguments(List(300_000) { "\"\"" }.joinToString(",", "[", "]"), 20_000, work = 100_000)).jsonArray
        empties.size shouldBe 3126 // 3125 values x 32 work each, then the "[…]" marker
        empties.last().jsonPrimitive.content shouldBe "[…]"
        val longKeys = (1..2000).joinToString(",", "{", "}") { "\"${"k".repeat(10_000)}$it\":1" }
        Json.parseToJsonElement(redactArguments(longKeys, 20_000, work = 100_000)).jsonObject.size shouldBe 11 // 10 keys + marker
    }

    test("token-shaped JSON keys are redacted and the output stays JSON") {
        val out = redactArguments("""{"ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123":1,"ok":2}""")
        out shouldBe """{"[REDACTED:github_token]":1,"ok":2}"""
    }

    test("URL passwords may hold commas; a comma after the host still ends the userinfo") {
        redactTokens("https://user:pa,ss@h.com/x") shouldBe "https://[REDACTED:url_credentials]@h.com/x"
        redactTokens("git clone https://bob:s3cr,et@github.com/x") shouldBe "git clone https://[REDACTED:url_credentials]@github.com/x"
        redactTokens("https://u:p@h.com,me@x.com") shouldBe "https://[REDACTED:url_credentials]@h.com,me@x.com"
        redactTokens("https://a:b@c:d@host/x") shouldBe "https://[REDACTED:url_credentials]@host/x"
        for (t in listOf("git@github.com:org/repo.git", "mailto:a@b.c")) redactTokens(t) shouldBe t
        Json.parseToJsonElement(redactTokens("""{"c":"https://u:p,\"q@h"}"""))
    }

    test("redaction work is bounded by input redacted, not output kept") {
        val big = (1..450).joinToString(",", "[", "]") { "\"${"A".repeat(20_001)}\"" }
        val values = Json.parseToJsonElement(redactArguments(big, 20_000, budget = 4000, work = 100_000))
            .jsonArray.map { it.jsonPrimitive.content }
        values.count { it != "[…]" } shouldBe 5 // 100k of work / 20k per value
    }

    test("a long URL password cut by the window leaves nothing after the scheme") {
        val pw = "a!".repeat(1500)
        val text = "Bearer ${"A".repeat(17_000)} https://u:$pw@h.com"
        val args = entryFor("""{"b":"$text"}""").args
        args shouldNotContain "a!a!"
        redactArguments(text, 20_000) shouldNotContain "a!a!" // non-JSON fallback
        redactWindowed(text, 20_000) shouldNotContain "a!a!" // the result path
        redactWindowed(text, 20_000) shouldEndWith "https://…"
    }

    test("free-text authorization_header assignments are redacted") {
        redactSecrets("authorization_header: abc123") shouldBe "authorization_header: [REDACTED:secret]"
    }

    test("a token cut by the window edge leaves no fragment, even after redaction shrinks the text") {
        val key = "sk-abcdefghijklmnopqrstuvwxyz0123"
        val args = entryFor("""{"b":"Bearer ${"A".repeat(19_975)} $key"}""").args
        args shouldNotContain "sk-abc"
        Json.parseToJsonElement(args)
        val result = entryFor("{}", "Bearer ${"A".repeat(19_975)} $key").result
        result shouldNotContain "sk-abc"
        redactArguments("Bearer ${"A".repeat(19_975)} $key", 20_000) shouldNotContain "sk-abc" // non-JSON fallback
    }

    test("redaction stops once the argument output is past its cap") {
        val big = (1..300).joinToString(",", "{", "}") { "\"k$it\":\"password=x ${"y".repeat(30_000)}\"" }
        entryFor(big).args shouldNotContain "password=x"
        // Deterministic stand-in for "fast" (~5 s before, every value redacted): past the budget nothing is redacted.
        val values = Json.parseToJsonElement(redactArguments(big, 20_000, budget = 4000)).jsonObject.values.map { it.jsonPrimitive.content }
        // Each value redacts to ~30 chars (its 20k window is cut), so the 4000-char budget stops after ~130 of 300.
        values.count { it != "[…]" } shouldBeLessThan 150
        values.last() shouldBe "[…]"
        values.first() shouldContain "password=[REDACTED:secret]"
    }

    test("comma-joined URLs keep the second host") {
        redactTokens("https://u:p@h.com,me@x.com") shouldBe "https://[REDACTED:url_credentials]@h.com,me@x.com"
    }
})
