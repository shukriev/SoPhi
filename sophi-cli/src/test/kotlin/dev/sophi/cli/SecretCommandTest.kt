package dev.sophi.cli

import dev.sophi.core.credentials.CredentialStore
import dev.sophi.core.credentials.LOCAL_ACTOR
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

internal class MemoryCredentials(vararg e: Pair<String, String>) : CredentialStore {
    val map = mutableMapOf(*e)
    override fun get(name: String, actor: String) = map[if (actor == LOCAL_ACTOR) name else "$actor/$name"]
    override fun set(name: String, value: String, actor: String) { map[name] = value }
    override fun remove(name: String, actor: String) { map.remove(name) }
    override fun names(actor: String) = map.keys.sorted()
}

class SecretCommandTest : FunSpec({

    test("secret set reads stdin and never echoes the value") {
        val store = MemoryCredentials()
        val out = mutableListOf<String>()
        SecretActions(store) { out += it }.set("cloudflare", readSecretValue("cloudflare", console = null, stdin = "tok-123456789\n".byteInputStream()))
        store.map["cloudflare"] shouldBe "tok-123456789"
        out.joinToString("\n") shouldNotContain "tok-123456789"
        out.joinToString("\n") shouldContain "cloudflare"
    }

    test("secret set warns on a short value") {
        val out = mutableListOf<String>()
        SecretActions(MemoryCredentials()) { out += it }.set("pin", "1234")
        out.joinToString("\n") shouldContain "Under 8 characters: stored, but not masked in output."
    }

    test("secret set refuses an empty value") {
        val store = MemoryCredentials()
        val out = mutableListOf<String>()
        SecretActions(store) { out += it }.set("x", readSecretValue("x", console = null, stdin = "\n".byteInputStream()))
        store.map.isEmpty() shouldBe true
        out.single() shouldContain "Nothing stored"
    }

    test("secret list prints names only") {
        val out = mutableListOf<String>()
        SecretActions(MemoryCredentials("b" to "v-123456789", "a" to "w-123456789")) { out += it }.list()
        out shouldBe listOf("a", "b")
    }

    test("secret remove forgets it") {
        val store = MemoryCredentials("a" to "v-123456789")
        val out = mutableListOf<String>()
        SecretActions(store) { out += it }.remove("a")
        store.map.isEmpty() shouldBe true
        out.single() shouldContain "Removed a"
    }
})
