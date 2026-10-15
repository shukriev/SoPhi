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

    // Both bonsai and Gemma stored the Bulgarian seed's master password; the old pattern only knew English.
    test("credentials are redacted in English, Bulgarian and German, with words between keyword and value") {
        listOf(
            "The user's primary password is lipa2024." to "lipa2024",
            "The user's bank PIN is 4417." to "4417",
            "The password for the wifi is tigerLily88." to "tigerLily88",
            "ПИН-ът на картата ми е 8812" to "8812",
            "паролата за wifi е sinyoMore77" to "sinyoMore77",
            "главната парола е lipa2024" to "lipa2024",
            "Meine Karten-PIN ist 5520" to "5520",
            "das WLAN-Passwort ist Sonnenblume9" to "Sonnenblume9",
            "Das Kennwort lautet kiefer2024" to "kiefer2024",
        ).forEach { (text, secret) -> redact(text) shouldNotContain secret }
    }

    test("a password manager is a fact, not a credential, in every language") {
        listOf(
            "The user uses Bitwarden as their password manager",
            "The user's password manager is Bitwarden",
            "Мениджърът ми на пароли е Bitwarden",
            "Mein Passwort-Manager ist Bitwarden",
            "Мила има уроци по пиано във вторник",
            "Das Team sitzt jetzt in Prenzlauer Berg",
        ).forEach { redact(it) shouldBe it }
    }
})
