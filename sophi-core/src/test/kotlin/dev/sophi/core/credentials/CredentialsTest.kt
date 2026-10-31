package dev.sophi.core.credentials

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private class FakeSecurity(var reply: Pair<Int, String> = 0 to "") {
    val calls = mutableListOf<Pair<List<String>, String>>()
    val run: (List<String>, String) -> Pair<Int, String> = { args, stdin -> calls += args to stdin; reply }
}

private val DUMP = """
keychain: "/Users/me/Library/Keychains/login.keychain-db"
class: "genp"
attributes:
    "acct"<blob>="cloudflare"
    "svce"<blob>="dev.sophi.credentials"
keychain: "/Users/me/Library/Keychains/login.keychain-db"
class: "genp"
attributes:
    "acct"<blob>="alice/gh"
    "svce"<blob>="dev.sophi.credentials"
keychain: "/Users/me/Library/Keychains/login.keychain-db"
class: "genp"
attributes:
    "acct"<blob>="other"
    "svce"<blob>="com.example.app"
""".trimIndent()

class CredentialsTest : FunSpec({

    test("set writes through security -i on stdin, never in args") {
        val sec = FakeSecurity()
        KeychainCredentialStore(run = sec.run).set("cloudflare", "tok-123456789")
        val (args, stdin) = sec.calls.single()
        args shouldBe listOf("security", "-i")
        stdin shouldContain """add-generic-password -U -s "dev.sophi.credentials" -a "cloudflare" -w "tok-123456789""""
    }

    test("set refuses a value with a line break") {
        val sec = FakeSecurity()
        shouldThrow<IllegalArgumentException> { KeychainCredentialStore(run = sec.run).set("k", "a\nb-12345678") }
        sec.calls shouldBe emptyList()
    }

    test("get returns the value and adds it to the mask") {
        val sec = FakeSecurity(0 to "tok-123456789\n")
        val mask = SecretMask()
        KeychainCredentialStore(mask = mask, run = sec.run).get("cloudflare") shouldBe "tok-123456789"
        sec.calls.single().first shouldBe listOf("security", "find-generic-password", "-s", "dev.sophi.credentials", "-a", "cloudflare", "-w")
        mask.mask("x tok-123456789 y") shouldBe "x [REDACTED:secret] y"
    }

    test("a missing entry is null") {
        KeychainCredentialStore(run = FakeSecurity(44 to "").run).get("nope") shouldBe null
    }

    test("a non-local actor prefixes the account") {
        val sec = FakeSecurity()
        KeychainCredentialStore(run = sec.run).set("k", "v12345678", actor = "alice")
        sec.calls.single().second shouldContain """-a "alice/k""""
    }

    test("names lists only this service's accounts for the actor") {
        val sec = FakeSecurity(0 to DUMP)
        val store = KeychainCredentialStore(run = sec.run)
        store.names() shouldBe listOf("cloudflare")
        store.names("alice") shouldBe listOf("gh")
    }

    test("mask leaves values under 8 chars alone") {
        val mask = SecretMask().apply { add("1234") }
        mask.mask("pin 1234") shouldBe "pin 1234"
    }

    test("mask replaces the longer value first") {
        val mask = SecretMask().apply { add("abcdefgh"); add("abcdefghij") }
        mask.mask("abcdefghij") shouldBe "[REDACTED:secret]"
    }
})
