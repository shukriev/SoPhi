package dev.sophi.ai.providers

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

class ProviderFactoryTest : FunSpec({
    test("buildClaudeProvider returns a provider named 'claude'") {
        val provider = buildClaudeProvider(apiKey = "sk-ant-test", model = "claude-opus-4-8")
        provider.name shouldBe "claude"
    }

    test("buildOpenAiCompatProvider returns a provider with the given name") {
        val provider = buildOpenAiCompatProvider(
            baseUrl = "http://localhost:11434/v1",
            apiKey = null,
            model = "qwen2.5:7b",
            name = "ollama"
        )
        provider.name shouldBe "ollama"
    }

    test("buildOpenAiCompatProvider defaults name to 'openai-compat'") {
        val provider = buildOpenAiCompatProvider(
            baseUrl = "http://localhost:8000/v1",
            apiKey = "sk-test",
            model = "Qwen/Qwen2.5-7B-Instruct"
        )
        provider.name shouldBe "openai-compat"
    }

    test("buildOpenAiCompatProvider accepts a custom requestTimeout") {
        // Local/reasoning models can take well over the 60s default to finish a completion;
        // this must be overridable rather than a fixed client-side timeout that aborts the
        // request out from under a model that's still generating.
        val provider = buildOpenAiCompatProvider(
            baseUrl = "http://localhost:11434/v1",
            apiKey = null,
            model = "qwen2.5:7b",
            requestTimeout = Duration.ofSeconds(300)
        )
        provider.name shouldBe "openai-compat"
    }

    test("buildOpenAiCompatProvider accepts a custom maxRetries") {
        // The OpenAI Java SDK retries maxRetries times, each subject to the full
        // requestTimeout — so effective worst-case latency is requestTimeout * (maxRetries+1).
        // A slow model that's consistently near the timeout should be able to opt out of
        // retries entirely rather than silently waiting multiples of the configured timeout.
        val provider = buildOpenAiCompatProvider(
            baseUrl = "http://localhost:11434/v1",
            apiKey = null,
            model = "qwen2.5:7b",
            maxRetries = 0
        )
        provider.name shouldBe "openai-compat"
    }

    test("buildProviderFromType returns a claude provider when apiKey is given") {
        val provider = buildProviderFromType("claude", apiKey = "sk-ant-test", baseUrl = null, model = "claude-opus-4-8")
        provider.name shouldBe "claude"
    }

    test("buildProviderFromType throws ProviderConfigException with the custom message when claude has no apiKey and no env var is present") {
        // Relies on ANTHROPIC_API_KEY not being set to a real key in the test environment — same
        // acceptance AgentConfigurationTest.kt:39-46 already documents for the equivalent case.
        val ex = shouldThrow<ProviderConfigException> {
            buildProviderFromType(
                "claude", apiKey = null, baseUrl = null, model = "claude-opus-4-8",
                missingApiKeyMessage = "custom missing key message"
            )
        }
        ex.message shouldBe "custom missing key message"
    }

    test("buildProviderFromType returns an openai-compat provider for valid arguments") {
        val provider = buildProviderFromType(
            "openai-compat", apiKey = null, baseUrl = "http://localhost:11434/v1", model = "qwen2.5:7b"
        )
        provider.name shouldBe "openai-compat"
    }

    test("buildProviderFromType throws ProviderConfigException with the custom message when openai-compat has no baseUrl") {
        val ex = shouldThrow<ProviderConfigException> {
            buildProviderFromType(
                "openai-compat", apiKey = null, baseUrl = null, model = "qwen2.5:7b",
                missingBaseUrlMessage = "custom missing url message"
            )
        }
        ex.message shouldBe "custom missing url message"
    }

    test("buildProviderFromType throws ProviderConfigException with the default message for an unknown type") {
        val ex = shouldThrow<ProviderConfigException> {
            buildProviderFromType("bogus", apiKey = null, baseUrl = null, model = "some-model")
        }
        ex.message shouldBe "Unknown provider type: bogus (expected 'claude' or 'openai-compat')"
    }

    test("buildProviderFromType throws ProviderConfigException with a custom unknownTypeMessage when one is passed") {
        val ex = shouldThrow<ProviderConfigException> {
            buildProviderFromType(
                "bogus", apiKey = null, baseUrl = null, model = "some-model",
                unknownTypeMessage = "custom unknown type message"
            )
        }
        ex.message shouldBe "custom unknown type message"
    }

    test("buildProviderFromType matches provider type case-insensitively") {
        val provider = buildProviderFromType("Claude", apiKey = "sk-ant-test", baseUrl = null, model = "claude-opus-4-8")
        provider.name shouldBe "claude"
    }

    test("ProviderConfigException is an IllegalArgumentException") {
        ProviderConfigException("x").shouldBeInstanceOf<IllegalArgumentException>()
    }
})
