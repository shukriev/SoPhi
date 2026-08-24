package dev.sophi.companion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
            maxTokens = 8192,
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

    test("resolveApiKey does NOT fall back to ANTHROPIC_API_KEY for a local openai-compat provider") {
        // A local Ollama/vLLM server should never receive an Anthropic key as its bearer token
        // just because the variable happens to be exported in the user's shell.
        val store = SettingsStore(createTempDirectory("sophi-companion-settings-test").resolve("companion.json"))
        val settings = CompanionSettings(providerType = "openai-compat", apiKey = null)

        store.resolveApiKey(settings) shouldBe null
    }

    test("resolveApiKey still honours an explicit key for openai-compat (vLLM behind auth)") {
        val store = SettingsStore(createTempDirectory("sophi-companion-settings-test").resolve("companion.json"))
        val settings = CompanionSettings(providerType = "openai-compat", apiKey = "vllm-token")

        store.resolveApiKey(settings) shouldBe "vllm-token"
    }

    test("maxTokens defaults to 4096, matching RuntimeBuilder's default") {
        CompanionSettings().maxTokens shouldBe 4096
    }

    test("a file written before maxTokens existed still loads, defaulting maxTokens") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val path = dir.resolve("companion.json")
        path.writeText("""{"providerType":"claude","model":"claude-sonnet-4-5","contextWindowTokens":200000}""")

        val loaded = SettingsStore(path).load()

        loaded?.maxTokens shouldBe 4096
        loaded?.model shouldBe "claude-sonnet-4-5"
    }

    test("a valid claude config has no validation error") {
        CompanionSettings().validationError() shouldBe null
    }

    test("a valid local config has no validation error") {
        val settings = CompanionSettings(
            providerType = "openai-compat",
            model = "qwen3:8b",
            baseUrl = "http://localhost:11434/v1",
            contextWindowTokens = 32768,
            maxTokens = 8192
        )

        settings.validationError() shouldBe null
    }

    test("openai-compat without a baseUrl is rejected with an actionable message") {
        val settings = CompanionSettings(providerType = "openai-compat", model = "qwen3:8b", baseUrl = null)

        settings.validationError() shouldContain "baseUrl is required"
    }

    test("openai-compat with a blank baseUrl is rejected too") {
        val settings = CompanionSettings(providerType = "openai-compat", model = "qwen3:8b", baseUrl = "   ")

        settings.validationError() shouldContain "baseUrl is required"
    }

    test("an unknown providerType is rejected") {
        CompanionSettings(providerType = "gemini").validationError() shouldContain "Unknown providerType"
    }

    test("a blank model is rejected") {
        CompanionSettings(model = "  ").validationError() shouldContain "model must not be blank"
    }

    test("non-positive token counts are rejected") {
        CompanionSettings(contextWindowTokens = 0).validationError() shouldContain "contextWindowTokens"
        CompanionSettings(maxTokens = 0).validationError() shouldContain "maxTokens"
    }

    test("maxTokens larger than the context window is rejected") {
        val settings = CompanionSettings(contextWindowTokens = 8192, maxTokens = 16384)

        settings.validationError() shouldContain "must not exceed"
    }

    test("memoryEnabled defaults to false and embedding fields default to null/1536") {
        val settings = CompanionSettings()
        settings.memoryEnabled shouldBe false
        settings.embeddingModel shouldBe null
        settings.embeddingBaseUrl shouldBe null
        settings.embeddingApiKey shouldBe null
        settings.embeddingDimensions shouldBe 1536
    }

    test("memoryEnabled without an embeddingModel is rejected") {
        val settings = CompanionSettings(memoryEnabled = true, embeddingModel = null, embeddingBaseUrl = "http://localhost:11434/v1")

        settings.validationError() shouldContain "embeddingModel is required"
    }

    test("memoryEnabled without an embeddingBaseUrl is rejected") {
        val settings = CompanionSettings(memoryEnabled = true, embeddingModel = "nomic-embed-text", embeddingBaseUrl = null)

        settings.validationError() shouldContain "embeddingBaseUrl is required"
    }

    test("memoryEnabled with both embedding fields present has no validation error") {
        val settings = CompanionSettings(
            memoryEnabled = true, embeddingModel = "nomic-embed-text", embeddingBaseUrl = "http://localhost:11434/v1"
        )

        settings.validationError() shouldBe null
    }

    test("voiceEnabled defaults to false and voice path fields default to null") {
        val settings = CompanionSettings()

        settings.voiceEnabled shouldBe false
        settings.whisperBinaryPath shouldBe null
        settings.whisperModelPath shouldBe null
        settings.piperPythonPath shouldBe null
        settings.piperVoicePath shouldBe null
        settings.pttHotkey shouldBe "Right Option"
        settings.validationError() shouldBe null
    }

    test("voiceEnabled with all four paths present has no validation error") {
        val settings = CompanionSettings(
            voiceEnabled = true,
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
            voiceEnabled = true,
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

    test("voiceEnabled alone is sufficient — the four path fields are no longer required") {
        val settings = CompanionSettings(voiceEnabled = true)

        settings.validationError() shouldBe null
    }

    test("memory settings round-trip through SettingsStore save/load") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        val settings = CompanionSettings(
            memoryEnabled = true, embeddingModel = "nomic-embed-text",
            embeddingBaseUrl = "http://localhost:11434/v1", embeddingApiKey = "emb-key",
            embeddingDimensions = 768
        )

        store.save(settings)

        store.load() shouldBe settings
    }

    test("hubPort defaults to 8765") {
        CompanionSettings(providerType = ProviderTypes.CLAUDE, model = "m").hubPort shouldBe 8765
    }

    test("hubPort round-trips through SettingsStore save/load") {
        val dir = createTempDirectory("sophi-companion-settings-test")
        val store = SettingsStore(dir.resolve("companion.json"))
        store.save(CompanionSettings(providerType = ProviderTypes.CLAUDE, model = "m", hubPort = 9999))
        store.load()?.hubPort shouldBe 9999
    }
})
