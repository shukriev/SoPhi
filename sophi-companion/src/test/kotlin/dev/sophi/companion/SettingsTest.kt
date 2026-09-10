package dev.sophi.companion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun singleProfileSettings(profile: LlmProfile): CompanionSettings =
    CompanionSettings(profiles = listOf(profile), activeProfileName = profile.name)

class SettingsTest : FunSpec({
    test("load() returns null when no settings file exists yet") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))

        store.load() shouldBe null
    }

    test("save() then load() round-trips all fields") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        val profile = LlmProfile(
            name = "Local",
            providerType = "openai-compat",
            model = "llama3",
            baseUrl = "http://localhost:11434/v1",
            apiKey = "sk-test",
            contextWindowTokens = 32000,
            maxTokens = 8192
        )
        val settings = singleProfileSettings(profile).copy(
            sessionsDir = "/tmp/sessions",
            mcpConfigPath = "/tmp/mcp.json"
        )

        store.save(settings)

        store.load() shouldBe settings
    }

    test("ambientListeningEnabled defaults to false") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        val settings = singleProfileSettings(LlmProfile(name = "Default", providerType = "claude", model = "claude-sonnet-4-5"))

        store.save(settings)

        store.load()!!.ambientListeningEnabled shouldBe false
    }

    test("save() creates parent directories if they don't exist") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("nested/dir/companion.json"))

        store.save(CompanionSettings())

        store.load() shouldBe CompanionSettings()
    }

    test("resolveApiKey prefers the explicit setting over the environment variable") {
        val store = SettingsStore(createTempDirectory("sophi-companion-settings-test").resolve("companion.json"))
        val settings = singleProfileSettings(
            LlmProfile(name = "Default", providerType = ProviderTypes.CLAUDE, model = "m", apiKey = "explicit-key")
        )

        store.resolveApiKey(settings) shouldBe "explicit-key"
    }

    test("resolveApiKey falls back to null when neither the setting nor ANTHROPIC_API_KEY is present") {
        // This test assumes ANTHROPIC_API_KEY is not set in the CI/dev environment running the tests.
        // If it is set locally, this test is expected to fail there — that's a signal, not a bug.
        val store = SettingsStore(createTempDirectory("sophi-companion-settings-test").resolve("companion.json"))
        val settings = singleProfileSettings(
            LlmProfile(name = "Default", providerType = ProviderTypes.CLAUDE, model = "m", apiKey = null)
        )

        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            store.resolveApiKey(settings) shouldBe null
        }
    }

    test("resolveApiKey does NOT fall back to ANTHROPIC_API_KEY for a local openai-compat provider") {
        // A local Ollama/vLLM server should never receive an Anthropic key as its bearer token
        // just because the variable happens to be exported in the user's shell.
        val store = SettingsStore(createTempDirectory("sophi-companion-settings-test").resolve("companion.json"))
        val settings = singleProfileSettings(
            LlmProfile(name = "Default", providerType = "openai-compat", model = "m", baseUrl = "http://x/v1", apiKey = null)
        )

        store.resolveApiKey(settings) shouldBe null
    }

    test("resolveApiKey still honours an explicit key for openai-compat (vLLM behind auth)") {
        val store = SettingsStore(createTempDirectory("sophi-companion-settings-test").resolve("companion.json"))
        val settings = singleProfileSettings(
            LlmProfile(name = "Default", providerType = "openai-compat", model = "m", baseUrl = "http://x/v1", apiKey = "vllm-token")
        )

        store.resolveApiKey(settings) shouldBe "vllm-token"
    }

    test("resolveApiKey returns null rather than throwing when profiles is empty") {
        val store = SettingsStore(createTempDirectory("sophi-companion-settings-test").resolve("companion.json"))
        val settings = CompanionSettings(profiles = emptyList(), activeProfileName = "Default")

        store.resolveApiKey(settings) shouldBe null
    }

    test("activeProfile() returns the profile matching activeProfileName") {
        val a = LlmProfile(name = "A", providerType = ProviderTypes.CLAUDE, model = "m1")
        val b = LlmProfile(name = "B", providerType = ProviderTypes.CLAUDE, model = "m2")
        val settings = CompanionSettings(profiles = listOf(a, b), activeProfileName = "B")

        settings.activeProfile() shouldBe b
    }

    test("activeProfile() falls back to the first profile when activeProfileName matches nothing") {
        val a = LlmProfile(name = "A", providerType = ProviderTypes.CLAUDE, model = "m1")
        val settings = CompanionSettings(profiles = listOf(a), activeProfileName = "does-not-exist")

        settings.activeProfile() shouldBe a
    }

    test("CompanionSettings() defaults to one 'Default' profile, active") {
        val settings = CompanionSettings()

        settings.profiles shouldBe listOf(LlmProfile(name = "Default", providerType = ProviderTypes.CLAUDE, model = "claude-sonnet-4-5"))
        settings.activeProfileName shouldBe "Default"
        settings.activeProfile().maxTokens shouldBe 4096
        settings.validationError() shouldBe null
    }

    test("a pre-profiles file migrates its old top-level fields into a single Default profile") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val path = dir.resolve("companion.json")
        path.writeText(
            """{"providerType":"openai-compat","model":"qwen3:8b","baseUrl":"http://localhost:11434/v1",
              |"apiKey":"k","contextWindowTokens":32768,"maxTokens":8192,"requestTimeoutSeconds":120,
              |"memoryEnabled":true,"embeddingModel":"nomic-embed-text","embeddingBaseUrl":"http://x/v1",
              |"embeddingApiKey":"ek","embeddingDimensions":768,"workspaceDir":"/kept/workspace"}""".trimMargin()
        )

        val loaded = SettingsStore(path).load()

        loaded?.activeProfileName shouldBe "Default"
        loaded?.profiles shouldBe listOf(
            LlmProfile(
                name = "Default", providerType = "openai-compat", model = "qwen3:8b",
                baseUrl = "http://localhost:11434/v1", apiKey = "k",
                contextWindowTokens = 32768, maxTokens = 8192, requestTimeoutSeconds = 120,
                memoryEnabled = true, embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://x/v1",
                embeddingApiKey = "ek", embeddingDimensions = 768
            )
        )
        loaded?.workspaceDir shouldBe "/kept/workspace"
    }

    test("a pre-profiles file with only some old keys still migrates, defaulting the rest") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val path = dir.resolve("companion.json")
        path.writeText("""{"providerType":"claude","model":"claude-sonnet-4-5","contextWindowTokens":200000}""")

        val loaded = SettingsStore(path).load()

        loaded?.activeProfileName shouldBe "Default"
        loaded?.activeProfile() shouldBe LlmProfile(
            name = "Default", providerType = "claude", model = "claude-sonnet-4-5", contextWindowTokens = 200000
        )
    }

    test("a file with an empty profiles array also migrates rather than staying empty") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val path = dir.resolve("companion.json")
        path.writeText("""{"providerType":"claude","model":"claude-sonnet-4-5","profiles":[]}""")

        val loaded = SettingsStore(path).load()

        loaded?.profiles?.size shouldBe 1
        loaded?.activeProfileName shouldBe "Default"
    }

    test("a valid claude config has no validation error") {
        CompanionSettings().validationError() shouldBe null
    }

    test("a valid local config has no validation error") {
        val settings = singleProfileSettings(
            LlmProfile(
                name = "Local", providerType = "openai-compat", model = "qwen3:8b",
                baseUrl = "http://localhost:11434/v1", contextWindowTokens = 32768, maxTokens = 8192
            )
        )

        settings.validationError() shouldBe null
    }

    test("openai-compat without a baseUrl is rejected with an actionable message") {
        val settings = singleProfileSettings(
            LlmProfile(name = "Local", providerType = "openai-compat", model = "qwen3:8b", baseUrl = null)
        )

        settings.validationError() shouldContain "baseUrl is required"
    }

    test("openai-compat with a blank baseUrl is rejected too") {
        val settings = singleProfileSettings(
            LlmProfile(name = "Local", providerType = "openai-compat", model = "qwen3:8b", baseUrl = "   ")
        )

        settings.validationError() shouldContain "baseUrl is required"
    }

    test("an unknown providerType is rejected") {
        val settings = singleProfileSettings(LlmProfile(name = "Default", providerType = "gemini", model = "m"))

        settings.validationError() shouldContain "Unknown providerType"
    }

    test("a blank model is rejected") {
        val settings = singleProfileSettings(LlmProfile(name = "Default", providerType = ProviderTypes.CLAUDE, model = "  "))

        settings.validationError() shouldContain "model must not be blank"
    }

    test("non-positive token counts are rejected") {
        singleProfileSettings(
            LlmProfile(name = "Default", providerType = ProviderTypes.CLAUDE, model = "m", contextWindowTokens = 0)
        ).validationError() shouldContain "contextWindowTokens"
        singleProfileSettings(
            LlmProfile(name = "Default", providerType = ProviderTypes.CLAUDE, model = "m", maxTokens = 0)
        ).validationError() shouldContain "maxTokens"
    }

    test("maxTokens larger than the context window is rejected") {
        val settings = singleProfileSettings(
            LlmProfile(name = "Default", providerType = ProviderTypes.CLAUDE, model = "m", contextWindowTokens = 8192, maxTokens = 16384)
        )

        settings.validationError() shouldContain "must not exceed"
    }

    test("empty profiles is rejected") {
        CompanionSettings(profiles = emptyList(), activeProfileName = "Default").validationError() shouldContain "profiles must not be empty"
    }

    test("activeProfileName not matching any profile is rejected") {
        val settings = CompanionSettings(
            profiles = listOf(LlmProfile(name = "A", providerType = ProviderTypes.CLAUDE, model = "m")),
            activeProfileName = "B"
        )

        settings.validationError() shouldContain "activeProfileName 'B' does not match any profile"
    }

    test("memoryEnabled defaults to false and embedding fields default to null/1536") {
        val profile = CompanionSettings().activeProfile()
        profile.memoryEnabled shouldBe false
        profile.embeddingModel shouldBe null
        profile.embeddingBaseUrl shouldBe null
        profile.embeddingApiKey shouldBe null
        profile.embeddingDimensions shouldBe 1536
    }

    test("memoryEnabled without an embeddingModel is rejected") {
        val settings = singleProfileSettings(
            LlmProfile(
                name = "Default", providerType = ProviderTypes.CLAUDE, model = "m",
                memoryEnabled = true, embeddingModel = null, embeddingBaseUrl = "http://localhost:11434/v1"
            )
        )

        settings.validationError() shouldContain "embeddingModel is required"
    }

    test("memoryEnabled without an embeddingBaseUrl is rejected") {
        val settings = singleProfileSettings(
            LlmProfile(
                name = "Default", providerType = ProviderTypes.CLAUDE, model = "m",
                memoryEnabled = true, embeddingModel = "nomic-embed-text", embeddingBaseUrl = null
            )
        )

        settings.validationError() shouldContain "embeddingBaseUrl is required"
    }

    test("memoryEnabled with both embedding fields present has no validation error") {
        val settings = singleProfileSettings(
            LlmProfile(
                name = "Default", providerType = ProviderTypes.CLAUDE, model = "m",
                memoryEnabled = true, embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://localhost:11434/v1"
            )
        )

        settings.validationError() shouldBe null
    }

    test("memory settings round-trip through SettingsStore save/load, per profile") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        val settings = singleProfileSettings(
            LlmProfile(
                name = "Default", providerType = ProviderTypes.CLAUDE, model = "m",
                memoryEnabled = true, embeddingModel = "nomic-embed-text",
                embeddingBaseUrl = "http://localhost:11434/v1", embeddingApiKey = "emb-key",
                embeddingDimensions = 768
            )
        )

        store.save(settings)

        store.load() shouldBe settings
    }

    test("sttEnabled and ttsEnabled default to false and voice path fields default to null") {
        val settings = CompanionSettings()

        settings.sttEnabled shouldBe false
        settings.ttsEnabled shouldBe false
        settings.whisperBinaryPath shouldBe null
        settings.whisperModelPath shouldBe null
        settings.piperPythonPath shouldBe null
        settings.piperVoicePath shouldBe null
        settings.pttHotkey shouldBe "Right Option"
        settings.validationError() shouldBe null
    }

    test("sttEnabled and ttsEnabled with all four paths present has no validation error") {
        val settings = CompanionSettings(
            sttEnabled = true,
            ttsEnabled = true,
            whisperBinaryPath = "/usr/local/bin/whisper",
            whisperModelPath = "/models/ggml-base.bin",
            piperPythonPath = "/usr/local/bin/python3",
            piperVoicePath = "/models/en_US-voice.onnx"
        )

        settings.validationError() shouldBe null
    }

    test("piperPythonPath round-trips through its stable JSON key piperBinaryPath") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        val settings = CompanionSettings(
            sttEnabled = true,
            ttsEnabled = true,
            whisperBinaryPath = "/usr/local/bin/whisper",
            whisperModelPath = "/models/ggml-base.bin",
            piperPythonPath = "/usr/local/bin/python3",
            piperVoicePath = "/models/en_US-voice.onnx"
        )

        store.save(settings)

        // The JSON key on disk must still be "piperBinaryPath" — a bare Kotlin rename would
        // silently drop an existing user's configured value as an unknown key.
        dir.resolve("companion.json").readText() shouldContain "\"piperBinaryPath\""
        store.load() shouldBe settings
    }

    test("sttEnabled/ttsEnabled alone are sufficient — the four path fields are no longer required") {
        val settings = CompanionSettings(sttEnabled = true, ttsEnabled = true)

        settings.validationError() shouldBe null
    }

    test("sttEnabled and ttsEnabled are independent — either can be set without the other") {
        CompanionSettings(sttEnabled = true).validationError() shouldBe null
        CompanionSettings(ttsEnabled = true).validationError() shouldBe null
    }

    test("hubPort defaults to 8765") {
        CompanionSettings().hubPort shouldBe 8765
    }

    test("hubPort round-trips through SettingsStore save/load") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        store.save(CompanionSettings(hubPort = 9999))
        store.load()?.hubPort shouldBe 9999
    }

    test("workspaceDir defaults to ~/.sophi/workspace") {
        val settings = CompanionSettings()
        settings.workspaceDir shouldBe System.getProperty("user.home") + "/.sophi/workspace"
    }

    test("workspaceDir round-trips through SettingsStore save/load") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        store.save(CompanionSettings(workspaceDir = "/tmp/workspace"))
        store.load()?.workspaceDir shouldBe "/tmp/workspace"
    }

    test("multiple profiles round-trip through SettingsStore save/load") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        val a = LlmProfile(name = "Default", providerType = ProviderTypes.CLAUDE, model = "claude-sonnet-4-5")
        val b = LlmProfile(
            name = "Local Ollama", providerType = ProviderTypes.OPENAI_COMPAT, model = "qwen3:8b",
            baseUrl = "http://localhost:11434/v1", contextWindowTokens = 32768
        )
        val settings = CompanionSettings(profiles = listOf(a, b), activeProfileName = "Local Ollama")

        store.save(settings)

        store.load() shouldBe settings
    }

    test("requestTimeoutSeconds defaults to 300, long enough for a local reasoning model's hidden thinking") {
        CompanionSettings().activeProfile().requestTimeoutSeconds shouldBe 300
    }

    test("a non-positive requestTimeoutSeconds is rejected") {
        val settings = singleProfileSettings(
            LlmProfile(name = "Default", providerType = ProviderTypes.CLAUDE, model = "m", requestTimeoutSeconds = 0)
        )

        settings.validationError() shouldContain "requestTimeoutSeconds"
    }

    test("checkIns defaults to one work-log entry, obsidianVaultPath and jiraBaseUrl default to null") {
        val settings = CompanionSettings()

        settings.obsidianVaultPath shouldBe null
        settings.jiraBaseUrl shouldBe null
        settings.checkIns shouldBe listOf(
            CheckIn("work-log", "What have you worked on since last check-in?", "0 11,14,17 * * *")
        )
        settings.validationError() shouldBe null
    }

    test("a blank checkIns name is rejected") {
        val settings = CompanionSettings(checkIns = listOf(CheckIn("  ", "question?", "0 11 * * *")))

        settings.validationError() shouldContain "non-blank name"
    }

    test("a blank checkIns question is rejected") {
        val settings = CompanionSettings(checkIns = listOf(CheckIn("work-log", "  ", "0 11 * * *")))

        settings.validationError() shouldContain "non-blank question"
    }

    test("an invalid checkIns cronExpression is rejected with the check-in's name in the message") {
        val settings = CompanionSettings(checkIns = listOf(CheckIn("work-log", "question?", "not a cron")))

        settings.validationError() shouldContain "work-log"
    }

    test("obsidianVaultPath, jiraBaseUrl, and checkIns round-trip through SettingsStore save/load") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        val settings = CompanionSettings(
            obsidianVaultPath = "/Users/me/vault",
            jiraBaseUrl = "https://jira.example.com",
            checkIns = listOf(
                CheckIn("work-log", "What have you worked on?", "0 11,14,17 * * *"),
                CheckIn("weekly-review", "How was your week?", "0 9 * * MON")
            )
        )

        store.save(settings)

        store.load() shouldBe settings
    }

    test("a file written before checkIns existed still loads, defaulting to the one work-log entry") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val path = dir.resolve("companion.json")
        path.writeText("""{"providerType":"claude","model":"claude-sonnet-4-5"}""")

        SettingsStore(path).load()?.checkIns shouldBe listOf(
            CheckIn("work-log", "What have you worked on since last check-in?", "0 11,14,17 * * *")
        )
    }
})
