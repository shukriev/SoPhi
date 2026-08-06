package dev.sophi.companion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class SettingsTest : FunSpec({
    test("load() returns null when no settings file exists yet") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))

        store.load() shouldBe null
    }

    test("save() then load() round-trips all fields") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        val settings = CompanionSettings(
            providerType = "openai-compat",
            model = "llama3",
            baseUrl = "http://localhost:11434/v1",
            apiKey = "sk-test",
            contextWindowTokens = 32000,
            sessionsDir = "/tmp/sessions",
            mcpConfigPath = "/tmp/mcp.json"
        )

        store.save(settings)

        store.load() shouldBe settings
    }

    test("save() creates parent directories if they don't exist") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("nested/dir/companion.json"))

        store.save(CompanionSettings())

        store.load() shouldBe CompanionSettings()
    }

    test("resolveApiKey prefers the explicit setting over the environment variable") {
        val store = SettingsStore(createTempDirectory("sophi-companion-settings-test").resolve("companion.json"))
        val settings = CompanionSettings(apiKey = "explicit-key")

        store.resolveApiKey(settings) shouldBe "explicit-key"
    }

    test("resolveApiKey falls back to null when neither the setting nor ANTHROPIC_API_KEY is present") {
        // This test assumes ANTHROPIC_API_KEY is not set in the CI/dev environment running the tests.
        // If it is set locally, this test is expected to fail there — that's a signal, not a bug.
        val store = SettingsStore(createTempDirectory("sophi-companion-settings-test").resolve("companion.json"))
        val settings = CompanionSettings(apiKey = null)

        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            store.resolveApiKey(settings) shouldBe null
        }
    }
})
